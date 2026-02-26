package com.bucketadapter.helpers;

import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;

public final class AdapterHelper {

  private AdapterHelper() {}

  private static final Pattern BUCKET_PREFIX = Pattern.compile("^/*([^/]+)(?:/(.*))?$");

  public record RemoteRef(String bucket, String keyOrPrefix) {}

  /** Parse "bucket/path/to/object" -> (bucket, "path/to/object") ; prefix peut être "" */
  public static RemoteRef parseRemote(String remote) {
    if (remote == null || remote.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    String path = remote.trim();

    // Support gs://bucket/key
    if (path.startsWith("gs://")) {
      path = path.substring(5);
    }

    // Enlève les / de tête (utile si key commence par / après gs://)
    while (path.startsWith("/")) {
      path = path.substring(1);
    }

    Matcher m = BUCKET_PREFIX.matcher(path);
    if (!m.matches()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    String bucket = m.group(1);
    String keyOrPrefix = (m.group(2) == null) ? "" : m.group(2);

    // Nettoie aussi un éventuel "/" au début du key
    while (keyOrPrefix.startsWith("/")) {
      keyOrPrefix = keyOrPrefix.substring(1);
    }

    if (bucket == null || bucket.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    return new RemoteRef(bucket, keyOrPrefix);
  }

  /** Valide que remote cible un objet (pas un dossier) */
  public static RemoteRef requireObjectKey(RemoteRef ref) {
    String key = ref.keyOrPrefix();
    if (key == null || key.isBlank() || key.endsWith("/")) {
      throw new InvalidBucketPathException("Invalid path.");
    }
    return ref;
  }

  public static void requirePayload(byte[] object) {
    if (object == null) {
      throw new InvalidBucketPathException("Invalid request.");
    }
  }

  public static RemoteRef requireKeyOrPrefix(RemoteRef ref) {
    String kp = ref.keyOrPrefix();
    if (kp == null || kp.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }
    return ref;
  }

  public static String normalizeListPrefix(String keyOrPrefix) {
    if (keyOrPrefix == null || keyOrPrefix.isBlank()) {
      return "";
    }
    return keyOrPrefix.endsWith("/") ? keyOrPrefix : keyOrPrefix + "/";
  }

  public static void requireShareExpirationSeconds(int expirationTimeSeconds) {
    // AWS S3 presigned URL: max 7 days = 604800 seconds
    if (expirationTimeSeconds < 1 || expirationTimeSeconds > 604800) {
      throw new InvalidBucketPathException("Invalid request.");
    }
  }

  public static void requireBucketName(String bucket) {
    if (bucket == null || bucket.isBlank()) {
      throw new InvalidBucketPathException("Invalid path.");
    }
  }

  public static void requireObjectKeyString(String key) {
    if (key == null || key.isBlank() || key.endsWith("/")) {
      throw new InvalidBucketPathException("Invalid path.");
    }
  }

  public static void throwIfBatchDeleteHadErrors(DeleteObjectsResponse resp) {
    if (resp == null || !resp.hasErrors() || resp.errors() == null || resp.errors().isEmpty()) {
      return;
    }

    Set<String> NOT_FOUND = Set.of("NoSuchKey", "NoSuchVersion", "NoSuchBucket");
    Set<String> INVALID = Set.of("InvalidRequest", "InvalidArgument", "MalformedXML");

    boolean anyNotFound =
        resp.errors().stream()
            .map(err -> err.code())
            .anyMatch(code -> code != null && NOT_FOUND.contains(code));

    boolean anyInvalid =
        resp.errors().stream()
            .map(err -> err.code())
            .anyMatch(code -> code != null && INVALID.contains(code));

    if (anyNotFound) {
      throw new BucketObjectNotFoundException("Resource not found.");
    }
    if (anyInvalid) {
      throw new InvalidBucketPathException("Invalid path.");
    }

    throw new BucketOperationException(
        "Operation failed.",
        new IllegalStateException("Provider reported partial delete failure."));
  }
}
