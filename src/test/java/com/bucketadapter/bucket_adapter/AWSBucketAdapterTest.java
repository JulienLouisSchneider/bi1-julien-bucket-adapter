package com.bucketadapter.bucket_adapter;

import com.bucketadapter.adapter.impl.AWSBucketAdapterImpl;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
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
  private AWSBucketAdapterImpl adapter;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    adapter = new AWSBucketAdapterImpl(s3Client);
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
            "photos/2024/cover.jpg",
            "photos/2024/january/cat.jpg",
            "photos/2024/february/dog.jpg"),
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

    ArgumentCaptor<PutObjectRequest> reqCaptor =
        ArgumentCaptor.forClass(PutObjectRequest.class);
    ArgumentCaptor<RequestBody> bodyCaptor =
        ArgumentCaptor.forClass(RequestBody.class);
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
    NoSuchBucketException noBucket =
        NoSuchBucketException.builder().message("Missing").build();
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
            "photos/2024/cover.jpg",
            "photos/2024/january/cat.jpg",
            "photos/2024/february/dog.jpg"),
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
}
