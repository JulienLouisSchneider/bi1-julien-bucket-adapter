package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import com.bucketadapter.helpers.AdapterHelper;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.*;
import org.springframework.stereotype.Component;
import com.bucketadapter.config.GcpStorageConfig;

import java.util.*;

@Component("GCP")
public class GCPBucketAdapterImpl implements BucketAdapter {

  private final Storage storage;

  public GCPBucketAdapterImpl(Storage storage) {
    this.storage = storage;
  }

  @Override
  public void upload(String remote, byte[] object) {
    AdapterHelper.requirePayload(object); // null check :contentReference[oaicite:2]{index=2}
    var ref =
        AdapterHelper.requireObjectKey(
            AdapterHelper.parseRemote(
                remote)); // interdit "" et "/" :contentReference[oaicite:3]{index=3}

    try {
      BlobId id = BlobId.of(ref.bucket(), ref.keyOrPrefix());
      BlobInfo info = BlobInfo.newBuilder(id).build();
      storage.create(info, object); // overwrite par défaut

    } catch (StorageException e) {
      throw AdapterHelper.mapGcsException(e);
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public byte[] download(String remote) {
    return new byte[0];
  }

  @Override
  public void delete(String remote, boolean recursive) {
    var ref = AdapterHelper.parseRemote(remote);
    String bucket = ref.bucket();
    String keyOrPrefix = ref.keyOrPrefix();

    // Delete "single object" (non-recursive)
    if (!recursive) {
      if (keyOrPrefix == null || keyOrPrefix.isBlank() || keyOrPrefix.endsWith("/")) {
        throw new InvalidBucketPathException("Invalid path.");
      }
      try {
        boolean deleted = storage.delete(com.google.cloud.storage.BlobId.of(bucket, keyOrPrefix));
        if (!deleted) {
          throw new BucketObjectNotFoundException("Resource not found.");
        }
        return;
      } catch (com.google.cloud.storage.StorageException e) {
        throw AdapterHelper.mapGcsException(e);
      } catch (RuntimeException e) {
        throw new BucketOperationException("Operation failed.", e);
      }
    }

    // Recursive delete (prefix)
    if (keyOrPrefix == null || keyOrPrefix.isBlank()) {
      // évite un delete “tout le bucket”
      throw new InvalidBucketPathException("Invalid path.");
    }

    String prefix = keyOrPrefix.endsWith("/") ? keyOrPrefix : (keyOrPrefix + "/");

    final int BATCH_SIZE = 100;
    var batch = new java.util.ArrayList<com.google.cloud.storage.BlobId>(BATCH_SIZE);
    boolean foundAny = false;

    try {
      var page =
          storage.list(bucket, com.google.cloud.storage.Storage.BlobListOption.prefix(prefix));

      for (var blob : page.iterateAll()) {
        foundAny = true;
        batch.add(com.google.cloud.storage.BlobId.of(bucket, blob.getName()));

        if (batch.size() == BATCH_SIZE) {
          storage.delete(batch);
          batch.clear();
        }
      }

      if (!batch.isEmpty()) {
        storage.delete(batch);
        batch.clear();
      }

      if (!foundAny) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }

    } catch (com.google.cloud.storage.StorageException e) {
      throw AdapterHelper.mapGcsException(e);
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public List<String> list(String remote, boolean recursive) {
    var ref = AdapterHelper.parseRemote(remote);
    String bucket = ref.bucket();
    String prefix = ref.keyOrPrefix();

    if (prefix != null && !prefix.isBlank() && !prefix.endsWith("/")) {
      prefix = prefix + "/";
    }

    try {
      Set<String> results = new LinkedHashSet<>();
      List<Storage.BlobListOption> opts = new ArrayList<>();

      if (prefix != null && !prefix.isBlank()) {
        opts.add(Storage.BlobListOption.prefix(prefix));
      }
      if (!recursive) {
        opts.add(Storage.BlobListOption.currentDirectory());
      }

      Page<Blob> page = storage.list(bucket, opts.toArray(new Storage.BlobListOption[0]));

      for (Blob b : page.iterateAll()) {
        results.add(b.getName());
      }

      return new ArrayList<>(results);

    } catch (StorageException e) {
      throw AdapterHelper.mapGcsException(e); // ton mapping (404 -> not found, etc.)
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public String share(String remote, int expirationTime) {
    return "";
  }
}
