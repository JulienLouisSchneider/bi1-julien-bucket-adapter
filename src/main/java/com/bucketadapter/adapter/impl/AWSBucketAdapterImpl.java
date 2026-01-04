package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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

  public AWSBucketAdapterImpl(S3Client s3Client, S3Presigner s3Presigner) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
    this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
  }

  @Override
  public void upload(String remote, byte[] object) {

    if (remote == null || remote.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }
    if (object == null) {
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

    PutObjectRequest req = PutObjectRequest.builder().bucket(bucket).key(key).build();

    try {
      s3Client.putObject(req, RequestBody.fromBytes(object));

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

  @Override
  public byte[] download(String remote) {
    return new byte[0];
  }

  @Override
  public void delete(String remote, boolean recursive) {

    if (remote == null || remote.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    final String bucket;
    final String prefix;

    try {
      String[] arrayRemote = BucketAndPrefix(remote);
      bucket = arrayRemote[BUCKET];
      prefix = arrayRemote[PREFIX];
    } catch (RuntimeException e) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (bucket == null || bucket.isBlank() || prefix == null || prefix.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    // Non-récursif => suppression d'un objet uniquement
    if (!recursive) {
      if (prefix.endsWith("/")) {
        throw new InvalidBucketPathException("Invalid path.");
      }
      // deleteOne() doit déjà mapper S3 -> exceptions génériques
      deleteOne(bucket, prefix);
      return;
    }

    // Récursif mais clé d'objet => supprimer l'objet seulement
    if (!prefix.endsWith("/")) {
      deleteOne(bucket, prefix);
      return;
    }

    // Récursif sur un préfixe => lister puis batch delete
    ListObjectsV2Request listReq =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
    List<ObjectIdentifier> batch = new ArrayList<>(1000);

    try {
      for (ListObjectsV2Response resp : s3Client.listObjectsV2Paginator(listReq)) {
        for (S3Object obj : resp.contents()) {
          batch.add(ObjectIdentifier.builder().key(obj.key()).build());
          if (batch.size() == 1000) {
            // flushBatchDelete() doit déjà mapper + utiliser BucketOperationException(String,
            // Throwable)
            flushBatchDelete(bucket, batch);
          }
        }
      }

      flushBatchDelete(bucket, batch);

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

  @Override
  public List<String> list(String remote, boolean recursive) {

    String bucket;
    String prefix;

    try {
      String[] arrayRemote = BucketAndPrefix(remote);
      bucket = arrayRemote[BUCKET];
      prefix = arrayRemote[PREFIX];

      if (bucket == null || bucket.isBlank()) {
        throw new InvalidBucketPathException("Invalid path.");
      }
    } catch (RuntimeException e) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (prefix != null && !prefix.isBlank() && !prefix.endsWith("/")) {
      prefix = prefix + "/";
    }

    var builder =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix == null ? "" : prefix);

    if (!recursive) {
      builder.delimiter("/");
    }

    var req = builder.build();

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

      if (e.statusCode() == 404) {
        throw new BucketObjectNotFoundException("Resource not found.");
      }

      throw new BucketOperationException("Operation failed.", e);

    } catch (SdkException e) {
      throw new BucketOperationException("Operation failed.", e);
    }
  }

  @Override
  public String share(String remote, int expirationTime) {

    if (remote == null || remote.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    if (expirationTime < 1 || expirationTime > 604800) { // 1s .. 7 jours
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
