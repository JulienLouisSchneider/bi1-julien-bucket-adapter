package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import com.bucketadapter.bucketadapterexceptions.BucketObjectNotFoundException;
import com.bucketadapter.bucketadapterexceptions.BucketOperationException;
import com.bucketadapter.bucketadapterexceptions.InvalidBucketPathException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("AWS")
public class AWSBucketAdapterImpl implements BucketAdapter {

  private final S3Client s3Client;

  private static final Pattern BUCKET_PREFIX = Pattern.compile("^/*([^/]+)(?:/(.*))?$");

  public static final int BUCKET = 0;
  public static final int PREFIX = 1;

  public AWSBucketAdapterImpl(S3Client s3Client) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
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
  public void delete(String remote, boolean recursive) {}

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
    return "";
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
      throw new BucketOperationException("AWS S3 error while checking existence of " + remote, e);
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
}
