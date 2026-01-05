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
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import com.bucketadapter.helpers.AwsS3AdapterHelper;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("AWS")
public class AWSBucketAdapterImpl implements BucketAdapter {

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  private static final Pattern BUCKET_PREFIX = Pattern.compile("^/*([^/]+)(?:/(.*))?$");

  public static final int BUCKET = 0;
  public static final int PREFIX = 1;
  private static final int DELETE_BATCH_SIZE = 1000;

  public AWSBucketAdapterImpl(S3Client s3Client, S3Presigner s3Presigner) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
    this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
  }

  @Override
  public void upload(String remote, byte[] object) {
    AwsS3AdapterHelper.requirePayload(object);

    AwsS3AdapterHelper.RemoteRef ref =
        AwsS3AdapterHelper.requireObjectKey(AwsS3AdapterHelper.parseRemote(remote));

    PutObjectRequest req =
        PutObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

    try {
      s3Client.putObject(req, RequestBody.fromBytes(object));

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw AwsS3AdapterHelper.mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public byte[] download(String remote) {
    AwsS3AdapterHelper.RemoteRef ref =
        AwsS3AdapterHelper.requireObjectKey(AwsS3AdapterHelper.parseRemote(remote));

    GetObjectRequest req =
        GetObjectRequest.builder().bucket(ref.bucket()).key(ref.keyOrPrefix()).build();

    try {
      return s3Client.getObject(req, ResponseTransformer.toBytes()).asByteArray();

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      throw AwsS3AdapterHelper.mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public void delete(String remote, boolean recursive) {
    AwsS3AdapterHelper.RemoteRef ref =
        AwsS3AdapterHelper.requireKeyOrPrefix(AwsS3AdapterHelper.parseRemote(remote));

    String bucket = ref.bucket();
    String keyOrPrefix = ref.keyOrPrefix();

    // Non-récursif => suppression d'un objet uniquement
    if (!recursive) {
      AwsS3AdapterHelper.requireObjectKey(ref); // refuse trailing "/"
      deleteOne(bucket, keyOrPrefix); // gère déjà le mapping d’erreurs
      return;
    }

    // Récursif mais clé d'objet => supprimer l'objet seulement
    if (!keyOrPrefix.endsWith("/")) {
      deleteOne(bucket, keyOrPrefix); // gère déjà le mapping d’erreurs
      return;
    }

    // Récursif sur un préfixe => lister puis batch delete
    deletePrefixRecursively(bucket, keyOrPrefix);
  }

  @Override
  public List<String> list(String remote, boolean recursive) {
    AwsS3AdapterHelper.RemoteRef ref = AwsS3AdapterHelper.parseRemote(remote);

    String bucket = ref.bucket();
    String prefix = AwsS3AdapterHelper.normalizeListPrefix(ref.keyOrPrefix());

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
      throw AwsS3AdapterHelper.mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public String share(String remote, int expirationTime) {
    if (remote == null || remote.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (expirationTime < 1 || expirationTime > 604800) {
      throw new InvalidBucketPathException("Invalid request.");
    }

    final String bucket;
    final String key;

    try {
      String[] arrayRemote = BucketAndPrefix(remote);
      bucket = arrayRemote[BUCKET];
      key = arrayRemote[PREFIX];
    } catch (RuntimeException e) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (bucket == null || bucket.isBlank() || key == null || key.isBlank() || key.endsWith("/")) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (!doesExists(remote)) {
      throw new InvalidBucketPathException("File not found.");
    }

    try {
      GetObjectRequest getReq = GetObjectRequest.builder().bucket(bucket).key(key).build();

      GetObjectPresignRequest presignReq =
          GetObjectPresignRequest.builder()
              .signatureDuration(Duration.ofSeconds(expirationTime))
              .getObjectRequest(getReq)
              .build();

      return s3Presigner.presignGetObject(presignReq).url().toString();

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      int sc = e.statusCode();

      if (sc == 404) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }
      if (sc == 400) {
        throw new InvalidBucketPathException("Invalid path.");
      }

      // 403/429/5xx, etc.
      throw new BucketOperationException("Operation failed.", e);

    } catch (SdkException e) {
      // credentials, réseau, timeouts, etc.
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  private boolean doesExists(String remote) {

    String[] arrayRemote = BucketAndPrefix(remote);
    String bucket = arrayRemote[BUCKET];
    String prefix = arrayRemote[PREFIX];

    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(prefix).build());
      return true;

    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw new BucketOperationException("Error while checking existence of " + remote, e);
    }
  }

  private void deletePrefixRecursively(String bucket, String prefix) {
    ListObjectsV2Request listReq =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();

    List<ObjectIdentifier> batch = new ArrayList<>(DELETE_BATCH_SIZE);

    try {
      for (ListObjectsV2Response resp : s3Client.listObjectsV2Paginator(listReq)) {
        for (S3Object obj : resp.contents()) {
          batch.add(ObjectIdentifier.builder().key(obj.key()).build());

          if (batch.size() == DELETE_BATCH_SIZE) {
            // flushBatchDelete() mappe déjà les erreurs + clear le batch
            flushBatchDelete(bucket, batch);
          }
        }
      }

      flushBatchDelete(bucket, batch);

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      // Ici on mappe uniquement les erreurs de la phase "listing"
      throw AwsS3AdapterHelper.mapS3Exception(e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);

    } finally {
      batch.clear(); // redondant car flushBatchDelete() clear déjà, mais ok en sécurité
    }
  }

  public static String[] BucketAndPrefix(String remote) {

    String path = remote.trim();
    Matcher m = BUCKET_PREFIX.matcher(path);

    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid path: " + remote);
    }

    String bucket = m.group(1);
    String prefix = (m.group(2) == null) ? "" : m.group(2);

    return new String[] {bucket, prefix};
  }

  private void deleteOne(String bucket, String key) {

    if (bucket == null || bucket.isBlank() || key == null || key.isBlank() || key.endsWith("/")) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      int sc = e.statusCode();

      if (sc == 404) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }
      if (sc == 400) {
        throw new InvalidBucketPathException("Invalid path.");
      }

      throw new BucketOperationException("Operation failed.", e);

    } catch (SdkException e) {

      throw new BucketOperationException("Operation failed.", e);
    }
  }

  private void flushBatchDelete(String bucket, List<ObjectIdentifier> batch) {
    if (batch == null) {
      throw new InvalidBucketPathException("Invalid request.");
    }
    if (batch.isEmpty()) return;

    if (bucket == null || bucket.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    try {
      DeleteObjectsResponse resp =
          s3Client.deleteObjects(
              DeleteObjectsRequest.builder()
                  .bucket(bucket)
                  .delete(Delete.builder().objects(batch).quiet(true).build())
                  .build());

      if (resp.hasErrors() && resp.errors() != null && !resp.errors().isEmpty()) {

        boolean anyNotFound =
            resp.errors().stream()
                .anyMatch(
                    e ->
                        "NoSuchKey".equalsIgnoreCase(e.code())
                            || "NoSuchVersion".equalsIgnoreCase(e.code()));

        boolean anyBucketNotFound =
            resp.errors().stream().anyMatch(e -> "NoSuchBucket".equalsIgnoreCase(e.code()));

        boolean anyInvalid =
            resp.errors().stream()
                .anyMatch(
                    e ->
                        "InvalidRequest".equalsIgnoreCase(e.code())
                            || "InvalidArgument".equalsIgnoreCase(e.code())
                            || "MalformedXML".equalsIgnoreCase(e.code()));

        if (anyBucketNotFound || anyNotFound) {
          throw new BucketObjectNotFoundException("Resource not found.");
        }
        if (anyInvalid) {
          throw new InvalidBucketPathException("Invalid path.");
        }

        // Cause neutre (pas de fuite S3)
        throw new BucketOperationException(
            "Operation failed.",
            new IllegalStateException("Provider reported partial delete failure."));
      }

    } catch (NoSuchBucketException e) {
      throw new BucketObjectNotFoundException("Resource not found.");

    } catch (S3Exception e) {
      int sc = e.statusCode();

      if (sc == 404) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }
      if (sc == 400) {
        throw new InvalidBucketPathException("Invalid path.");
      }

      throw new BucketOperationException("Operation failed.", e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);

    } finally {
      batch.clear();
    }
  }
}
