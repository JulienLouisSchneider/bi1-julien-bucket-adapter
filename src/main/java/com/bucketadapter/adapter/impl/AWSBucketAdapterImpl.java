package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;

import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.bucketadapter.helpers.AdapterHelper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component("AWS")
public class AWSBucketAdapterImpl implements BucketAdapter {

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  private static final int DELETE_BATCH_SIZE = 1000;

  public AWSBucketAdapterImpl(S3Client s3Client, S3Presigner s3Presigner) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
    this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
  }

  @Override
  public void upload(String remote, byte[] object) {
    AdapterHelper.requirePayload(object);

    AdapterHelper.RemoteRef ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    PutObjectRequest req =
        PutObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

    try {
      s3Client.putObject(req, RequestBody.fromBytes(object));

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public byte[] download(String remote) {
    AdapterHelper.RemoteRef ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    GetObjectRequest req =
        GetObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

    try {
      return s3Client.getObject(req, ResponseTransformer.toBytes()).asByteArray();

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public void delete(String remote, boolean recursive) {
    AdapterHelper.RemoteRef ref =
        AdapterHelper.requireKeyOrPrefix(AdapterHelper.parseRemote(remote));

    String bucket = ref.bucket();
    String keyOrPrefix = ref.keyOrPrefix();

    if (!recursive) {
      AdapterHelper.requireObjectKey(ref);
      deleteOne(bucket, keyOrPrefix);
      return;
    }

    if (!keyOrPrefix.endsWith("/")) {
      deleteOne(bucket, keyOrPrefix);
      return;
    }

    deletePrefixRecursively(bucket, keyOrPrefix);
  }

  @Override
  public List<String> list(String remote, boolean recursive) {
    AdapterHelper.RemoteRef ref = AdapterHelper.parseRemote(remote);

    String bucket = ref.bucket();
    String prefix = AdapterHelper.normalizeListPrefix(ref.keyOrPrefix());

    ListObjectsV2Request.Builder builder =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);

    if (!recursive) {
      builder.delimiter("/");
    }

    ListObjectsV2Request req = builder.build();

    try {
      Set<String> results = new LinkedHashSet<>();

      for (ListObjectsV2Response resp : s3Client.listObjectsV2Paginator(req)) {
        if (!recursive) {
          resp.commonPrefixes().forEach(cp -> results.add(cp.prefix()));
        }
        resp.contents().forEach(obj -> results.add(obj.key()));
      }

      return new ArrayList<>(results);

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public String share(String remote, int expirationTime) {
    AdapterHelper.requireShareExpirationSeconds(expirationTime);

    AdapterHelper.RemoteRef ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    if (!doesExists(remote)) {
      throw new InvalidBucketPathException("File not found.");
    }

    try {
      GetObjectRequest getReq =
          GetObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

      GetObjectPresignRequest presignReq =
          GetObjectPresignRequest.builder()
              .signatureDuration(Duration.ofSeconds(expirationTime))
              .getObjectRequest(getReq)
              .build();

      return s3Presigner.presignGetObject(presignReq).url().toString();

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  private boolean doesExists(String remote) {
    AdapterHelper.RemoteRef ref = AdapterHelper.requireObjectKey(AdapterHelper.parseRemote(remote));

    HeadObjectRequest req =
        HeadObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

    try {
      s3Client.headObject(req);
      return true;

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      int sc = e.statusCode();

      if (sc == 404) {
        return false;
      }
      if (sc == 400) {
        throw new InvalidBucketPathException("Invalid path.");
      }

      throw new BucketOperationException("Operation failed.", e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  private void deletePrefixRecursively(String bucket, String prefix) {
    AdapterHelper.requireBucketName(bucket);

    ListObjectsV2Request listReq =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();

    List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);

    try {
      for (ListObjectsV2Response resp : s3Client.listObjectsV2Paginator(listReq)) {
        for (S3Object obj : resp.contents()) {
          batch.add(ObjectIdentifier.builder().key(obj.key()).build());

          if (batch.size() == DELETE_BATCH_SIZE) {
            flushBatchDelete(bucket, batch);
          }
        }
      }

      flushBatchDelete(bucket, batch);

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {

      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);

    } finally {
      batch.clear();
    }
  }

  private void deleteOne(String bucket, String key) {
    AdapterHelper.requireBucketName(bucket);
    AdapterHelper.requireObjectKeyString(key);

    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  private void flushBatchDelete(String bucket, List<ObjectIdentifier> batch) {
    if (batch == null) {
      throw new InvalidBucketPathException("Invalid request.");
    }
    if (batch.isEmpty()) return;

    AdapterHelper.requireBucketName(bucket);

    try {
      DeleteObjectsResponse resp =
          s3Client.deleteObjects(
              DeleteObjectsRequest.builder()
                  .bucket(bucket)
                  .delete(Delete.builder().objects(batch).quiet(true).build())
                  .build());

      AdapterHelper.throwIfBatchDeleteHadErrors(resp);

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);

    } finally {
      batch.clear();
    }
  }

  /** Mapping centralisé des erreurs S3 vers tes exceptions métier */
  public static RuntimeException mapS3Exception(S3Exception e) {
    int sc = e.statusCode();

    if (sc == 404) {
      return new BucketObjectNotFoundException("Resource not found.");
    }
    if (sc == 400) {
      return new InvalidBucketPathException("Invalid path.");
    }

    return new BucketOperationException("Operation failed.", e);
  }
}
