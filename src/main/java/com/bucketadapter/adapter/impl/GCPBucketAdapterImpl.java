package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import com.bucketadapter.helpers.AdapterHelper;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.HttpMethod;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.concurrent.TimeUnit;

@Component("GCP")
public class GCPBucketAdapterImpl implements BucketAdapter {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Storage est injecté (bean) et n'est jamais exposé; usage interne uniquement.")
  private final Storage storage;

  public GCPBucketAdapterImpl(Storage storage) {
    this.storage = storage;
  }

  @Override
  public void upload(String remote, byte[] object) {
    AdapterHelper.requirePayload(object);
    var ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    try {
      BlobId id = BlobId.of(ref.bucket(), ref.keyOrPrefix());
      BlobInfo info = BlobInfo.newBuilder(id).build();
      storage.create(info, object); // overwrite par défaut

    } catch (StorageException e) {
      throw mapGcsException(e);
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public byte[] download(String remote) {

    var ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    BlobId id = BlobId.of(ref.bucket(), ref.keyOrPrefix());

    try (ReadChannel reader = storage.reader(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {

      ByteBuffer buf = ByteBuffer.allocate(64 * 1024);

      while (reader.read(buf) > 0) {
        buf.flip();
        out.write(buf.array(), 0, buf.limit());
        buf.clear();
      }

      return out.toByteArray();

    } catch (StorageException e) {
      throw mapGcsException(e);
    } catch (Exception e) {
      throw new BucketOperationException("Operation failed.", e);
    }
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
        throw mapGcsException(e);
      } catch (RuntimeException e) {
        throw new BucketOperationException("Operation failed.", e);
      }
    }

    if (keyOrPrefix == null || keyOrPrefix.isBlank()) {

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
      throw mapGcsException(e);
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
      throw mapGcsException(e);
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public String share(String remote, int expirationTime) {
    // Valide 1..604800 (7 jours) + remote cible un objet
    AdapterHelper.requireShareExpirationSeconds(expirationTime);
    var ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    try {
      BlobId blobId = BlobId.of(ref.bucket(), ref.keyOrPrefix());
      Blob existing = storage.get(blobId);
      if (existing == null) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }

      BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

      URL url =
          storage.signUrl(
              blobInfo,
              expirationTime,
              TimeUnit.SECONDS,
              Storage.SignUrlOption.withV4Signature(),
              Storage.SignUrlOption.httpMethod(HttpMethod.GET));

      return url.toString();

    } catch (StorageException e) {
      throw mapGcsException(e);
    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @SuppressFBWarnings(
      value = "UPM_UNCALLED_PRIVATE_METHOD",
      justification = "Méthode conservée pour usage futur / lisibilité.")
  private boolean doesExists(String remote) {
    AdapterHelper.RemoteRef ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    BlobId id = BlobId.of(ref.bucket(), ref.keyOrPrefix());

    try {
      Blob blob = storage.get(id);
      return blob != null;

    } catch (StorageException e) {
      int code = e.getCode();

      // Bucket introuvable / pas accessible (ou autre 404 "bucket-level")
      if (code == 404) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }
      if (code == 400) {
        throw new InvalidBucketPathException("Invalid path.");
      }

      throw new BucketOperationException("Operation failed.", e);

    } catch (RuntimeException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  public static RuntimeException mapGcsException(StorageException e) {
    int code = e.getCode();
    if (code == 404) return new BucketObjectNotFoundException("Resource not found.");
    if (code == 400) return new InvalidBucketPathException("Invalid path.");
    return new BucketOperationException("Operation failed.", e);
  }
}
