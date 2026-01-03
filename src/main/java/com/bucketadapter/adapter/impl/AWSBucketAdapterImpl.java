package com.bucketadapter.adapter.impl;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

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
  public void upload(String remote, byte[] object) {}

  @Override
  public byte[] download(String remote) {
    return new byte[0];
  }

  @Override
  public void delete(String remote, boolean recursive) {}

  @Override
  public List<String> list(String remote, boolean recursive) {

    String[] arrayRemote = BucketAndPrefix(remote);
    String bucket = arrayRemote[BUCKET];
    String prefix = arrayRemote[PREFIX];

    if (prefix != null && !prefix.isBlank() && !prefix.endsWith("/")) {
      prefix = prefix + "/";
    }

    var builder =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix == null ? "" : prefix);

    if (!recursive) {
      builder.delimiter("/");
    }

    var req = builder.build();

    Set<String> results = new LinkedHashSet<>();

    s3Client.listObjectsV2Paginator(req).stream()
        .forEach(
            resp -> {
              if (!recursive) {
                resp.commonPrefixes().forEach(cp -> results.add(cp.prefix()));
              }
              resp.contents().forEach(obj -> results.add(obj.key()));
            });

    return new ArrayList<>(results);
  }

  @Override
  public String share(String remote, int expirationTime) {
    return "";
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
