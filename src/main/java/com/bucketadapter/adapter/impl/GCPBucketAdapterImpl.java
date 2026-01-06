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
  public void delete(String remote, boolean recursive) {}

    @Override
    public List<String> list(String remote, boolean recursive) {
        return List.of();
    }

    @Override
    public String share(String remote, int expirationTime) {
        return "";
    }
}
