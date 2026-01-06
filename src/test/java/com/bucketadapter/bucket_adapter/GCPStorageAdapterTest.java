package com.bucketadapter.bucket_adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bucketadapter.adapter.impl.GCPBucketAdapterImpl;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class GCPStorageAdapterTest {

  private Storage storage;
  private GCPBucketAdapterImpl adapter;
  private Method doesExistsMethod;

  @BeforeEach
  void setUp() throws NoSuchMethodException {
    storage = mock(Storage.class);
    adapter = new GCPBucketAdapterImpl(storage);
    doesExistsMethod = GCPBucketAdapterImpl.class.getDeclaredMethod("doesExists", String.class);
    doesExistsMethod.setAccessible(true);
  }

  @Test
  void downloadReadsAllBytesFromChannel() throws IOException {
    // Given
    ReadChannel reader = mock(ReadChannel.class);
    when(storage.reader(any(BlobId.class))).thenReturn(reader);
    AtomicInteger invocation = new AtomicInteger();
    when(reader.read(any(ByteBuffer.class)))
        .thenAnswer(
            call -> {
              ByteBuffer buffer = call.getArgument(0, ByteBuffer.class);
              int index = invocation.getAndIncrement();
              if (index == 0) {
                byte[] chunk = new byte[] {1, 2, 3};
                buffer.put(chunk);
                return chunk.length;
              }
              if (index == 1) {
                byte[] chunk = new byte[] {4, 5};
                buffer.put(chunk);
                return chunk.length;
              }
              return -1;
            });

    // When
    byte[] bytes = adapter.download("  media/photos/kitten.jpg  ");

    // Then
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, bytes);
    ArgumentCaptor<BlobId> blobIdCaptor = ArgumentCaptor.forClass(BlobId.class);
    verify(storage).reader(blobIdCaptor.capture());
    BlobId blobId = blobIdCaptor.getValue();
    assertEquals("media", blobId.getBucket());
    assertEquals("photos/kitten.jpg", blobId.getName());
  }

  @Test
  void downloadMapsStorageExceptionToBucketObjectNotFound() {
    // Given
    when(storage.reader(any(BlobId.class))).thenThrow(new StorageException(404, "missing object"));

    // When / Then
    assertThrows(
        BucketObjectNotFoundException.class, () -> adapter.download("archive/files/missing.txt"));
  }

  @Test
  void downloadWrapsUnexpectedErrorsAsBucketOperationException() throws IOException {
    // Given
    ReadChannel reader = mock(ReadChannel.class);
    when(storage.reader(any(BlobId.class))).thenReturn(reader);
    when(reader.read(any(ByteBuffer.class))).thenThrow(new IOException("io failure"));

    // When / Then
    assertThrows(
        BucketOperationException.class, () -> adapter.download("archive/photos/broken.jpg"));
  }

  @Test
  void uploadCreatesBlobWithProvidedPayload() {
    // Given
    byte[] payload = new byte[] {9, 8, 7};

    // When
    adapter.upload("media/photos/hero.jpg", payload);

    // Then
    ArgumentCaptor<BlobInfo> infoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
    ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(storage).create(infoCaptor.capture(), bytesCaptor.capture());
    BlobInfo info = infoCaptor.getValue();
    assertEquals("media", info.getBucket());
    assertEquals("photos/hero.jpg", info.getName());
    assertArrayEquals(payload, bytesCaptor.getValue());
  }

  @Test
  void uploadMapsStorageExceptionToDomain() {
    // Given
    when(storage.create(any(BlobInfo.class), any(byte[].class)))
        .thenThrow(new StorageException(400, "bad request"));

    // When / Then
    assertThrows(
        InvalidBucketPathException.class, () -> adapter.upload("media/photos/hero.jpg", new byte[] {1}));
  }

  @Test
  void uploadWrapsRuntimeExceptions() {
    // Given
    when(storage.create(any(BlobInfo.class), any(byte[].class)))
        .thenThrow(new IllegalStateException("boom"));

    // When
    BucketOperationException ex =
        assertThrows(
            BucketOperationException.class, () -> adapter.upload("media/photos/hero.jpg", new byte[] {1}));

    // Then
    assertTrue(ex.getCause() instanceof IllegalStateException);
  }

  @Test
  void deleteNonRecursiveRemovesSingleObject() {
    // Given
    when(storage.delete(any(BlobId.class))).thenReturn(true);

    // When
    adapter.delete("archive/photos/cover.jpg", false);

    // Then
    ArgumentCaptor<BlobId> blobIdCaptor = ArgumentCaptor.forClass(BlobId.class);
    verify(storage).delete(blobIdCaptor.capture());
    BlobId blobId = blobIdCaptor.getValue();
    assertEquals("archive", blobId.getBucket());
    assertEquals("photos/cover.jpg", blobId.getName());
  }

  @Test
  void deleteNonRecursiveThrowsWhenObjectMissing() {
    // Given
    when(storage.delete(any(BlobId.class))).thenReturn(false);

    // When
    BucketOperationException ex =
        assertThrows(
            BucketOperationException.class, () -> adapter.delete("archive/ghost.txt", false));

    // Then
    assertTrue(ex.getCause() instanceof BucketObjectNotFoundException);
  }

  @Test
  void deleteNonRecursiveRejectsDirectoryPaths() {
    // Given / When / Then
    assertThrows(InvalidBucketPathException.class, () -> adapter.delete("media/photos/", false));
  }

  @Test
  void deleteNonRecursiveMapsStorageException() {
    // Given
    when(storage.delete(any(BlobId.class))).thenThrow(new StorageException(400, "bad path"));

    // When / Then
    assertThrows(
        InvalidBucketPathException.class, () -> adapter.delete("media/photos/file.jpg", false));
  }

  @Test
  void deleteRecursiveDeletesObjectsInBatches() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class))).thenReturn(page);
    Blob january = mock(Blob.class);
    when(january.getName()).thenReturn("photos/2024/january/cat.jpg");
    Blob february = mock(Blob.class);
    when(february.getName()).thenReturn("photos/2024/february/dog.jpg");
    when(page.iterateAll()).thenReturn(List.of(january, february));
    when(storage.delete(any(Iterable.class)))
        .thenAnswer(
            invocation -> {
              Iterable<BlobId> ids = invocation.getArgument(0);
              List<String> names = new ArrayList<>();
              for (BlobId id : ids) {
                names.add(id.getName());
              }
              assertEquals(
                  List.of("photos/2024/january/cat.jpg", "photos/2024/february/dog.jpg"), names);
              return List.of(true, true);
            });

    // When
    adapter.delete("media/photos/2024", true);

    // Then
    ArgumentCaptor<Storage.BlobListOption[]> optionsCaptor =
        ArgumentCaptor.forClass(Storage.BlobListOption[].class);
    verify(storage).list(eq("media"), optionsCaptor.capture());
    Storage.BlobListOption[] options = optionsCaptor.getValue();
    assertEquals(1, options.length);
    verify(storage).delete(any(Iterable.class));
  }

  @Test
  void deleteRecursiveThrowsWhenNothingFound() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class))).thenReturn(page);
    when(page.iterateAll()).thenReturn(List.of());

    // When
    BucketOperationException ex =
        assertThrows(
            BucketOperationException.class, () -> adapter.delete("media/photos/empty/", true));

    // Then
    assertTrue(ex.getCause() instanceof BucketObjectNotFoundException);
  }

  @Test
  void deleteRecursiveRejectsMissingPrefixes() {
    // Given / When / Then
    assertThrows(InvalidBucketPathException.class, () -> adapter.delete("media", true));
  }

  @Test
  void deleteRecursiveDeletesLargePrefixesInMultipleBatches() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class))).thenReturn(page);
    List<Blob> blobs = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      Blob blob = mock(Blob.class);
      when(blob.getName()).thenReturn("photos/2024/img-" + i + ".jpg");
      blobs.add(blob);
    }
    when(page.iterateAll()).thenReturn(blobs);
    List<Integer> batchSizes = new ArrayList<>();
    when(storage.delete(any(Iterable.class)))
        .thenAnswer(
            invocation -> {
              batchSizes.add(sizeOfIterable(invocation.getArgument(0)));
              return List.of();
            });

    // When
    adapter.delete("media/photos/2024/", true);

    // Then
    verify(storage, times(2)).delete(any(Iterable.class));
    assertEquals(List.of(100, 20), batchSizes);
  }

  @Test
  void deleteRecursiveMapsStorageExceptionFromList() {
    // Given
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class)))
        .thenThrow(new StorageException(400, "bad prefix"));

    // When / Then
    assertThrows(
        InvalidBucketPathException.class, () -> adapter.delete("media/photos/2024/", true));
  }

  @Test
  void deleteRecursiveMapsStorageExceptionFromDeleteBatch() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class))).thenReturn(page);
    Blob blob = mock(Blob.class);
    when(blob.getName()).thenReturn("photos/2024/file.jpg");
    when(page.iterateAll()).thenReturn(List.of(blob));
    when(storage.delete(any(Iterable.class)))
        .thenThrow(new StorageException(400, "cannot delete"));

    // When / Then
    assertThrows(
        InvalidBucketPathException.class, () -> adapter.delete("media/photos/2024/", true));
  }

  @Test
  void listRecursiveNormalizesPrefixAndDeduplicates() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("media"), any(Storage.BlobListOption[].class))).thenReturn(page);
    Blob cover = mock(Blob.class);
    when(cover.getName()).thenReturn("photos/2024/cover.jpg");
    Blob duplicate = mock(Blob.class);
    when(duplicate.getName()).thenReturn("photos/2024/cover.jpg");
    Blob hero = mock(Blob.class);
    when(hero.getName()).thenReturn("photos/2024/hero.jpg");
    when(page.iterateAll()).thenReturn(List.of(cover, duplicate, hero));

    // When
    List<String> names = adapter.list("media/photos/2024", true);

    // Then
    assertEquals(List.of("photos/2024/cover.jpg", "photos/2024/hero.jpg"), names);
    ArgumentCaptor<Storage.BlobListOption[]> optionsCaptor =
        ArgumentCaptor.forClass(Storage.BlobListOption[].class);
    verify(storage).list(eq("media"), optionsCaptor.capture());
    assertEquals(1, optionsCaptor.getValue().length);
  }

  @Test
  void listNonRecursiveAddsCurrentDirectoryOption() {
    // Given
    Page<Blob> page = mock(Page.class);
    when(storage.list(eq("archive"), any(Storage.BlobListOption[].class))).thenReturn(page);
    Blob doc = mock(Blob.class);
    when(doc.getName()).thenReturn("docs/cover.pdf");
    when(page.iterateAll()).thenReturn(List.of(doc));

    // When
    List<String> names = adapter.list("  archive/docs  ", false);

    // Then
    assertEquals(List.of("docs/cover.pdf"), names);
    ArgumentCaptor<Storage.BlobListOption[]> optionsCaptor =
        ArgumentCaptor.forClass(Storage.BlobListOption[].class);
    verify(storage).list(eq("archive"), optionsCaptor.capture());
    assertEquals(2, optionsCaptor.getValue().length);
  }

  @Test
  void listMapsStorageExceptions() {
    // Given
    when(storage.list(anyString(), any(Storage.BlobListOption[].class)))
        .thenThrow(new StorageException(400, "bad prefix"));

    // When / Then
    assertThrows(InvalidBucketPathException.class, () -> adapter.list("media/photos", true));
  }

  @Test
  void listWrapsRuntimeExceptions() {
    // Given
    when(storage.list(anyString(), any(Storage.BlobListOption[].class)))
        .thenThrow(new IllegalStateException("boom"));

    // When
    BucketOperationException ex =
        assertThrows(BucketOperationException.class, () -> adapter.list("media/photos", true));

    // Then
    assertTrue(ex.getCause() instanceof IllegalStateException);
  }

  @Test
  void shareReturnsSignedUrlForExistingBlob() throws MalformedURLException {
    // Given
    Blob blob = mock(Blob.class);
    when(storage.get(any(BlobId.class))).thenReturn(blob);
    URL presigned = new URL("https://cdn.example.com/photos/cover.jpg?token=abc");
    when(storage.signUrl(
            any(BlobInfo.class),
            eq(120L),
            eq(TimeUnit.SECONDS),
            any(Storage.SignUrlOption.class),
            any(Storage.SignUrlOption.class)))
        .thenReturn(presigned);

    // When
    String url = adapter.share("media/photos/cover.jpg", 120);

    // Then
    assertEquals(presigned.toString(), url);
    ArgumentCaptor<BlobId> blobIdCaptor = ArgumentCaptor.forClass(BlobId.class);
    verify(storage).get(blobIdCaptor.capture());
    assertEquals("media", blobIdCaptor.getValue().getBucket());
    assertEquals("photos/cover.jpg", blobIdCaptor.getValue().getName());
    ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
    verify(storage)
        .signUrl(
            blobInfoCaptor.capture(),
            eq(120L),
            eq(TimeUnit.SECONDS),
            any(Storage.SignUrlOption.class),
            any(Storage.SignUrlOption.class));
    assertEquals("media", blobInfoCaptor.getValue().getBucket());
    assertEquals("photos/cover.jpg", blobInfoCaptor.getValue().getName());
  }

  @Test
  void shareThrowsWhenBlobMissing() {
    // Given
    when(storage.get(any(BlobId.class))).thenReturn(null);

    // When
    BucketOperationException ex =
        assertThrows(
            BucketOperationException.class, () -> adapter.share("media/photos/ghost.jpg", 60));

    // Then
    assertTrue(ex.getCause() instanceof BucketObjectNotFoundException);
    verify(storage, never())
        .signUrl(
            any(BlobInfo.class),
            anyLong(),
            any(TimeUnit.class),
            any(Storage.SignUrlOption.class),
            any(Storage.SignUrlOption.class));
  }

  @Test
  void shareMapsStorageExceptionFromSignerToBucketObjectNotFound() throws MalformedURLException {
    // Given
    Blob blob = mock(Blob.class);
    when(storage.get(any(BlobId.class))).thenReturn(blob);
    when(storage.signUrl(
            any(BlobInfo.class),
            eq(45L),
            eq(TimeUnit.SECONDS),
            any(Storage.SignUrlOption.class),
            any(Storage.SignUrlOption.class)))
        .thenThrow(new StorageException(404, "bucket missing"));

    // When / Then
    assertThrows(
        BucketObjectNotFoundException.class, () -> adapter.share("media/photos/cover.jpg", 45));
  }

  @Test
  void doesExistsReturnsTrueWhenBlobFound() {
    // Given
    Blob blob = mock(Blob.class);
    when(storage.get(any(BlobId.class))).thenReturn(blob);

    // When
    boolean exists = invokeDoesExists("media/photos/cover.jpg");

    // Then
    assertTrue(exists);
  }

  @Test
  void doesExistsReturnsFalseWhenBlobMissing() {
    // Given
    when(storage.get(any(BlobId.class))).thenReturn(null);

    // When
    boolean exists = invokeDoesExists("media/photos/missing.jpg");

    // Then
    assertFalse(exists);
  }

  @Test
  void doesExistsMapsStorageExceptions() {
    // Given
    when(storage.get(any(BlobId.class))).thenThrow(new StorageException(404, "missing bucket"));

    // When / Then
    assertThrows(
        BucketObjectNotFoundException.class, () -> invokeDoesExists("media/photos/ghost.jpg"));

    // Given
    when(storage.get(any(BlobId.class))).thenThrow(new StorageException(400, "bad path"));

    // Then
    assertThrows(
        InvalidBucketPathException.class, () -> invokeDoesExists("media/photos/ghost.jpg"));
  }

  @Test
  void doesExistsMapsUnhandledStorageErrorsToBucketOperationException() {
    // Given
    when(storage.get(any(BlobId.class))).thenThrow(new StorageException(500, "boom"));

    // When / Then
    assertThrows(BucketOperationException.class, () -> invokeDoesExists("media/photos/broken.jpg"));
  }

  @Test
  void doesExistsWrapsRuntimeExceptions() {
    // Given
    when(storage.get(any(BlobId.class))).thenThrow(new IllegalStateException("panic"));

    // When
    BucketOperationException ex =
        assertThrows(BucketOperationException.class, () -> invokeDoesExists("media/photos/broken.jpg"));

    // Then
    assertTrue(ex.getCause() instanceof IllegalStateException);
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

  private static int sizeOfIterable(Iterable<BlobId> ids) {
    int count = 0;
    for (BlobId id : ids) {
      count++;
    }
    return count;
  }
}
