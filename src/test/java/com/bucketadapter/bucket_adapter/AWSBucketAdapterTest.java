package com.bucketadapter.bucket_adapter;

import com.bucketadapter.adapter.impl.AWSBucketAdapterImpl;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
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
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AWSBucketAdapterTest {

  private S3Client s3Client;
  private S3Presigner s3Presigner;
  private AWSBucketAdapterImpl adapter;
  private Method doesExistsMethod;
  private Method flushBatchDeleteMethod;

  @BeforeEach
  void setUp() throws Exception {
    s3Client = mock(S3Client.class);
    s3Presigner = mock(S3Presigner.class);
    adapter = new AWSBucketAdapterImpl(s3Client, s3Presigner);
    doesExistsMethod = AWSBucketAdapterImpl.class.getDeclaredMethod("doesExists", String.class);
    doesExistsMethod.setAccessible(true);
    flushBatchDeleteMethod =
        AWSBucketAdapterImpl.class.getDeclaredMethod("flushBatchDelete", String.class, List.class);
    flushBatchDeleteMethod.setAccessible(true);
  }

  @Test
  void listReturnsAllKeysFromPaginatorStream() {
    // Given
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

    // When
    List<String> keys = adapter.list("archive/photos/2024", true);

    // Then
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
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response singlePage =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("invoice.pdf").build())
            .build();
    when(paginator.iterator()).thenReturn(Stream.of(singlePage).iterator());

    // When
    List<String> keys = adapter.list("   finance-docs   ", false);

    // Then
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
    // Given
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

    // When
    List<String> keys = adapter.list("archive/photos/2024", true);

    // Then
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
    // Given
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

    // When
    List<String> keys = adapter.list("company/docs", false);

    // Then
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
    // Given
    byte[] payload = new byte[] {1, 2, 3, 4};

    // When
    adapter.upload("media/photos/hero.jpg", payload);

    // Then
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
    // Given
    NoSuchBucketException noBucket = NoSuchBucketException.builder().message("Missing").build();
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(noBucket);

    // When
    Executable action = () -> adapter.upload("archive/photos/cover.jpg", new byte[] {1});

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void uploadMapsBadRequestToInvalidBucketPath() {
    // Given
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("Bad request").build());

    // When
    Executable action = () -> adapter.upload("archive/photos/cover.jpg", new byte[] {1});

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void downloadReturnsBytesForValidRemote() {
    // Given
    byte[] payload = new byte[] {10, 20, 30};
    @SuppressWarnings("unchecked")
    ResponseBytes<GetObjectResponse> bytes = mock(ResponseBytes.class);
    when(bytes.asByteArray()).thenReturn(payload);
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenReturn(bytes);

    // When
    byte[] result = adapter.download("  archive/photos/cover.jpg  ");

    // Then
    assertArrayEquals(payload, result);
    ArgumentCaptor<GetObjectRequest> reqCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObject(reqCaptor.capture(), any(ResponseTransformer.class));
    GetObjectRequest req = reqCaptor.getValue();
    assertEquals("archive", req.bucket());
    assertEquals("photos/cover.jpg", req.key());
  }

  @Test
  void downloadMapsNoSuchBucketToBucketObjectNotFound() {
    // Given
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing bucket").build());

    // When
    Executable action = () -> adapter.download("archive/photos/cover.jpg");

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void downloadMapsBadRequestToInvalidBucketPath() {
    // Given
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("Bad request").build());

    // When
    Executable action = () -> adapter.download("archive/photos/cover.jpg");

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void deleteNonRecursiveSendsDeleteObjectRequest() {
    // Given - no special setup

    // When
    adapter.delete("media/photos/cover.jpg", false);

    // Then
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
    // Given
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

    // When
    adapter.delete("archive/photos/2024/", true);

    // Then
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
    // Given
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/cover.jpg", false);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void sharePresignsExistingObject() throws MalformedURLException {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url()).thenReturn(new URL("https://cdn.example.com/photos/cover.jpg?token=abc"));
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

    // When
    String url = adapter.share("archive/photos/cover.jpg", 120);

    // Then
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
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("Missing").build());

    // When
    Executable action = () -> adapter.share("archive/photos/ghost.jpg", 60);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
    verify(s3Presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void shareMapsNoSuchBucketToBucketObjectNotFound() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("Missing bucket").build());

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 100);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void listThrowsBucketObjectNotFoundWhenBucketMissing() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing").build());

    // When
    Executable action = () -> adapter.list("archive/photos", true);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void listMapsS3ExceptionToInvalidBucketPath() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> adapter.list("archive/photos", true);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void listWrapsSdkExceptionsAsBucketOperation() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.list("archive/photos", true);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void uploadMapsSdkExceptionsToBucketOperation() {
    // Given
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.upload("archive/photos/broken.jpg", new byte[] {9});

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void downloadMapsSdkExceptionToBucketOperation() {
    // Given
    when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.download("archive/photos/broken.jpg");

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void deleteRecursiveTreatsFilePathAsSingleObject() {
    // Given - no special setup

    // When
    adapter.delete("archive/photos/file.jpg", true);

    // Then
    verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    verify(s3Client, never()).listObjectsV2Paginator(any(ListObjectsV2Request.class));
  }

  @Test
  void deleteRecursiveWithoutPrefixThrowsInvalidPath() {
    // Given - no additional setup

    // When
    Executable action = () -> adapter.delete("archive", true);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void deleteNonRecursiveMapsS3ExceptionToInvalidPath() {
    // Given
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/file.jpg", false);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void deleteNonRecursiveWrapsSdkExceptions() {
    // Given
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.delete("archive/photos/file.jpg", false);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void deleteRecursiveMapsNoSuchBucketFromListCall() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void deleteRecursiveMapsS3ExceptionFromListCall() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void deleteRecursiveWrapsSdkExceptionsFromListCall() {
    // Given
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void deleteRecursiveMapsDeleteBatchNoSuchBucket() {
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("photos/2024/item.jpg").build())
            .build();
    when(paginator.iterator()).thenReturn(Stream.of(page).iterator());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void deleteRecursiveMapsDeleteBatchS3Exception() {
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("photos/2024/item.jpg").build())
            .build();
    when(paginator.iterator()).thenReturn(Stream.of(page).iterator());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void deleteRecursiveMapsDeleteBatchSdkException() {
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(S3Object.builder().key("photos/2024/item.jpg").build())
            .build();
    when(paginator.iterator()).thenReturn(Stream.of(page).iterator());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.delete("archive/photos/", true);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void deleteRecursivePrefixWithNoObjectsSkipsBatchDelete() {
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response empty = ListObjectsV2Response.builder().build();
    when(paginator.iterator()).thenReturn(Stream.of(empty).iterator());

    // When
    adapter.delete("archive/photos/", true);

    // Then
    verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  @Test
  void deleteRecursiveFlushesWhenBatchReachesLimit() {
    // Given
    ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
    when(s3Client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
    ListObjectsV2Response first = pageWithObjects(0, 1000);
    ListObjectsV2Response second = pageWithObjects(1000, 2);
    when(paginator.iterator()).thenReturn(Stream.of(first, second).iterator());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().build());

    // When
    adapter.delete("archive/photos/", true);

    // Then
    verify(s3Client, times(2)).deleteObjects(any(DeleteObjectsRequest.class));
  }

  @Test
  void shareThrowsBucketObjectNotFoundWhenHeadReportsMissingBucket() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing bucket").build());

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 30);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void shareThrowsInvalidWhenHeadReportsBadRequest() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 30);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void shareWrapsSdkExceptionsRaisedDuringHeadCheck() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 30);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void shareMapsS3ExceptionFromPresigner() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(500).message("bad").build());

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 45);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void shareWrapsSdkExceptionFromPresigner() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> adapter.share("archive/photos/cover.jpg", 45);

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void doesExistsReturnsFalseWhenS3Reports404() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());

    // When
    boolean exists = invokeDoesExists("archive/photos/missing.jpg");

    // Then
    assertFalse(exists);
  }

  @Test
  void doesExistsThrowsInvalidOnBadRequest() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(400).message("bad").build());

    // When
    Executable action = () -> invokeDoesExists("archive/photos/bad.txt");

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void doesExistsThrowsBucketObjectNotFoundWhenBucketMissing() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing").build());

    // When
    Executable action = () -> invokeDoesExists("archive/photos/file.txt");

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
  }

  @Test
  void doesExistsWrapsUnhandledS3Exceptions() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

    // When
    Executable action = () -> invokeDoesExists("archive/photos/file.txt");

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void doesExistsWrapsSdkExceptions() {
    // Given
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(SdkException.create("boom", null));

    // When
    Executable action = () -> invokeDoesExists("archive/photos/file.txt");

    // Then
    assertThrows(BucketOperationException.class, action);
  }

  @Test
  void flushBatchDeleteRejectsNullBatch() {
    // Given - no batch

    // When
    Executable action = () -> invokeFlushBatchDelete("archive", null);

    // Then
    assertThrows(InvalidBucketPathException.class, action);
  }

  @Test
  void flushBatchDeleteDoesNothingForEmptyBatch() {
    // Given
    ArrayList<ObjectIdentifier> batch = new ArrayList<>();

    // When
    invokeFlushBatchDelete("archive", batch);

    // Then
    verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
  }

  @Test
  void flushBatchDeleteClearsBatchEvenOnException() {
    // Given
    ArrayList<ObjectIdentifier> batch = new ArrayList<>();
    batch.add(ObjectIdentifier.builder().key("photos/2024/item.jpg").build());
    when(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("missing").build());

    // When
    Executable action = () -> invokeFlushBatchDelete("archive", batch);

    // Then
    assertThrows(BucketObjectNotFoundException.class, action);
    assertTrue(batch.isEmpty());
  }

  private boolean invokeDoesExists(String remote) {
    try {
      return (boolean) doesExistsMethod.invoke(adapter, remote);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private void invokeFlushBatchDelete(String bucket, List<ObjectIdentifier> batch) {
    try {
      flushBatchDeleteMethod.invoke(adapter, bucket, batch);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private ListObjectsV2Response pageWithObjects(int startIndex, int count) {
    S3Object[] objects = new S3Object[count];
    for (int i = 0; i < count; i++) {
      objects[i] = S3Object.builder().key("photos/2024/item-" + (startIndex + i) + ".jpg").build();
    }
    return ListObjectsV2Response.builder().contents(objects).build();
  }
}
