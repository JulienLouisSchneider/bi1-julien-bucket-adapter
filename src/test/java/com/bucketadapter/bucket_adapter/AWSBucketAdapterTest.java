package com.bucketadapter.bucket_adapter;

import com.bucketadapter.adapter.impl.AWSBucketAdapterImpl;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AWSBucketAdapterTest {

  private S3Client s3Client;
  private S3Presigner s3Presigner;
  private AWSBucketAdapterImpl adapter;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    s3Presigner = mock(S3Presigner.class);
    adapter = new AWSBucketAdapterImpl(s3Client, s3Presigner);
  }

  @Test
  void listReturnsAllKeysFromPaginatorStream() {
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);

    ListObjectsV2Response firstPage =
        ListObjectsV2Response.builder()
            .contents(
                S3Object.builder().key("photos/2024/img-1.jpg").build(),
                S3Object.builder().key("photos/2024/img-2.jpg").build())
            .build();
    ListObjectsV2Response secondPage =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("photos/2024/img-3.jpg").build())
            .build();

    when(paginator.iterator()).thenReturn(Stream.of(firstPage, secondPage).iterator());

    List<String> keys = adapter.list("archive/photos/2024", true);

    assertEquals(
        List.of("photos/2024/img-1.jpg", "photos/2024/img-2.jpg", "photos/2024/img-3.jpg"), keys);

    ArgumentCaptor<ListObjectsV2Request> reqCaptor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2Paginator(reqCaptor.capture());

    ListObjectsV2Request request = reqCaptor.getValue();
    assertEquals("archive", request.bucket());
    assertEquals("photos/2024/", request.prefix());
  }

  @Test
  void listTrimsRemoteAndUsesEmptyPrefixWhenPathMissing() {
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);

    ListObjectsV2Response singlePage =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("invoice.pdf").build())
            .build();

    when(paginator.iterator()).thenReturn(Stream.of(singlePage).iterator());

    List<String> keys = adapter.list("   finance-docs   ", false);

    assertEquals(List.of("invoice.pdf"), keys);

    ArgumentCaptor<ListObjectsV2Request> reqCaptor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2Paginator(reqCaptor.capture());

    ListObjectsV2Request request = reqCaptor.getValue();
    assertEquals("finance-docs", request.bucket());
    assertEquals("", request.prefix());
  }

  @Test
  void listRecursiveReturnsNestedKeysAndSkipsCommonPrefixes() {
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);

    ListObjectsV2Response firstPage =
        ListObjectsV2Response.builder()
            .commonPrefixes(CommonPrefix.builder().prefix("photos/2024/january/").build())
            .contents(
                S3Object.builder().key("photos/2024/cover.jpg").build(),
                S3Object.builder().key("photos/2024/january/cat.jpg").build())
            .build();
    ListObjectsV2Response secondPage =
        ListObjectsV2Response.builder()
            .commonPrefixes(CommonPrefix.builder().prefix("photos/2024/february/").build())
            .contents(
                S3Object.builder().key("photos/2024/february/dog.jpg").build(),
                S3Object.builder().key("photos/2024/january/cat.jpg").build())
            .build();

    when(paginator.iterator()).thenReturn(Stream.of(firstPage, secondPage).iterator());

    List<String> keys = adapter.list("archive/photos/2024", true);

    assertEquals(
        List.of(
            "photos/2024/cover.jpg", "photos/2024/january/cat.jpg", "photos/2024/february/dog.jpg"),
        keys);

    ArgumentCaptor<ListObjectsV2Request> reqCaptor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2Paginator(reqCaptor.capture());

    ListObjectsV2Request request = reqCaptor.getValue();
    assertEquals("archive", request.bucket());
    assertEquals("photos/2024/", request.prefix());
    assertNull(request.delimiter());
  }

  @Test
  void listNonRecursiveReturnsCommonPrefixesAndTopLevelObjects() {
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);

    ListObjectsV2Response singlePage =
        ListObjectsV2Response.builder()
            .commonPrefixes(
                CommonPrefix.builder().prefix("docs/2023/").build(),
                CommonPrefix.builder().prefix("docs/archives/").build())
            .contents(
                S3Object.builder().key("docs/cover.pdf").build(),
                S3Object.builder().key("docs/summary.txt").build())
            .build();

    when(paginator.iterator()).thenReturn(Stream.of(singlePage).iterator());

    List<String> keys = adapter.list("company/docs", false);

    assertEquals(
        List.of("docs/2023/", "docs/archives/", "docs/cover.pdf", "docs/summary.txt"), keys);

    ArgumentCaptor<ListObjectsV2Request> reqCaptor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2Paginator(reqCaptor.capture());

    ListObjectsV2Request request = reqCaptor.getValue();
    assertEquals("company", request.bucket());
    assertEquals("docs/", request.prefix());
    assertEquals("/", request.delimiter());
  }

  @Test
  void uploadSendsPutObjectRequestForValidRemote() throws IOException {
    byte[] payload = new byte[] {1, 2, 3, 4};

    adapter.upload("media/photos/hero.jpg", payload);

    ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
    ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
    verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

    PutObjectRequest request = reqCaptor.getValue();
    assertEquals("media", request.bucket());
    assertEquals("photos/hero.jpg", request.key());
    try (var input = bodyCaptor.getValue().contentStreamProvider().newStream()) {
      assertArrayEquals(payload, input.readAllBytes());
    }
  }

  @Test
  void uploadMapsMissingBucketToBucketObjectNotFound() {
    NoSuchBucketException noBucket = NoSuchBucketException.builder().message("Missing").build();
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(noBucket);

    assertThrows(
        BucketObjectNotFoundException.class,
        () -> adapter.upload("archive/photos/cover.jpg", new byte[] {1}));
  }

  @Test
  void uploadMapsBadRequestToInvalidBucketPath() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("Bad request").build());

    assertThrows(
        InvalidBucketPathException.class,
        () -> adapter.upload("archive/photos/cover.jpg", new byte[] {1}));
  }

  @Test
  void downloadReturnsBytesForValidRemote() {
    byte[] payload = new byte[] {10, 20, 30};
    @SuppressWarnings("unchecked")
    ResponseBytes<GetObjectResponse> bytes = mock(ResponseBytes.class);
    when(bytes.asByteArray()).thenReturn(payload);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(bytes);

    byte[] result = adapter.download("  archive/photos/cover.jpg  ");

    assertArrayEquals(payload, result);
    ArgumentCaptor<GetObjectRequest> reqCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObject(reqCaptor.capture(), any(ResponseTransformer.class));
    GetObjectRequest req = reqCaptor.getValue();
    assertEquals("archive", req.bucket());
    assertEquals("photos/cover.jpg", req.key());
  }

  @Test
  void downloadMapsNoSuchBucketToBucketObjectNotFound() {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing bucket").build());

    assertThrows(
        BucketObjectNotFoundException.class, () -> adapter.download("archive/photos/cover.jpg"));
  }

  @Test
  void downloadMapsBadRequestToInvalidBucketPath() {
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("Bad request").build());

    assertThrows(
        InvalidBucketPathException.class, () -> adapter.download("archive/photos/cover.jpg"));
  }

  @Test
  void deleteNonRecursiveSendsDeleteObjectRequest() {
    adapter.delete("media/photos/cover.jpg", false);

    ArgumentCaptor<DeleteObjectRequest> reqCaptor =
        ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(reqCaptor.capture());
    DeleteObjectRequest req = reqCaptor.getValue();
    assertEquals("media", req.bucket());
    assertEquals("photos/cover.jpg", req.key());
    verify(s3Client, never()).listObjectsV2Paginator(any(ListObjectsV2Request.class));
  }

  @Test
  void deleteRecursivePrefixUsesPaginatorAndBatchDelete() {
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response firstPage =
        ListObjectsV2Response.builder()
            .contents(
                S3Object.builder().key("photos/2024/cover.jpg").build(),
                S3Object.builder().key("photos/2024/january/cat.jpg").build())
            .build();
    ListObjectsV2Response secondPage =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("photos/2024/february/dog.jpg").build())
            .build();
    when(paginator.iterator()).thenReturn(Stream.of(firstPage, secondPage).iterator());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());

    adapter.delete("archive/photos/2024/", true);

    ArgumentCaptor<ListObjectsV2Request> listCaptor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2Paginator(listCaptor.capture());
    ListObjectsV2Request listReq = listCaptor.getValue();
    assertEquals("archive", listReq.bucket());
    assertEquals("photos/2024/", listReq.prefix());

    ArgumentCaptor<DeleteObjectsRequest> deleteCaptor =
        ArgumentCaptor.forClass(DeleteObjectsRequest.class);
    verify(s3Client).deleteObjects(deleteCaptor.capture());
    DeleteObjectsRequest deleteReq = deleteCaptor.getValue();
    assertEquals("archive", deleteReq.bucket());
    List<String> deletedKeys =
        deleteReq.delete().objects().stream().map(ObjectIdentifier::key).toList();
    assertEquals(
        List.of(
            "photos/2024/cover.jpg", "photos/2024/january/cat.jpg", "photos/2024/february/dog.jpg"),
        deletedKeys);
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void deleteNonRecursiveMapsMissingBucketToBucketObjectNotFound() {
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing").build());

    assertThrows(
        BucketObjectNotFoundException.class,
        () -> adapter.delete("archive/photos/cover.jpg", false));
  }

  @Test
  void sharePresignsExistingObject() throws MalformedURLException {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url()).thenReturn(new URL("https://cdn.example.com/photos/cover.jpg?token=abc"));
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

    String url = adapter.share("archive/photos/cover.jpg", 120);

    assertEquals("https://cdn.example.com/photos/cover.jpg?token=abc", url);

    ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
    verify(s3Client).headObject(headCaptor.capture());
    HeadObjectRequest headReq = headCaptor.getValue();
    assertEquals("archive", headReq.bucket());
    assertEquals("photos/cover.jpg", headReq.key());

    ArgumentCaptor<GetObjectPresignRequest> presignCaptor =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(s3Presigner).presignGetObject(presignCaptor.capture());
    GetObjectPresignRequest presignReq = presignCaptor.getValue();
    assertEquals(120, presignReq.signatureDuration().getSeconds());
    GetObjectRequest getReq = presignReq.getObjectRequest();
    assertEquals("archive", getReq.bucket());
    assertEquals("photos/cover.jpg", getReq.key());
  }

  @Test
  void shareThrowsWhenObjectMissing() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("Missing").build());

    assertThrows(
        InvalidBucketPathException.class, () -> adapter.share("archive/photos/ghost.jpg", 60));

    verify(s3Presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void shareMapsNoSuchBucketToBucketObjectNotFound() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing bucket").build());

    assertThrows(
        BucketObjectNotFoundException.class, () -> adapter.share("archive/photos/cover.jpg", 100));
  }
}
