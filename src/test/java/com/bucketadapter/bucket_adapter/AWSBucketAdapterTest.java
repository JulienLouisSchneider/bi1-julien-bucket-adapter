package com.bucketadapter.bucket_adapter;

import com.bucketadapter.adapter.impl.AWSBucketAdapterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    when(paginator.stream()).thenReturn(Stream.of(firstPage, secondPage));

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

    when(paginator.stream()).thenReturn(Stream.of(singlePage));

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

    when(paginator.stream()).thenReturn(Stream.of(firstPage, secondPage));

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

    when(paginator.stream()).thenReturn(Stream.of(singlePage));

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
}
