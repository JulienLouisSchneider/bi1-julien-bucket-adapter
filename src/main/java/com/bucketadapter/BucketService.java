package com.bucketadapter;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BucketService {

  private final BucketAdapterFactory bucketAdapterFactory;

  private BucketAdapter bucketAdapter() {
    return bucketAdapterFactory.getAdapter();
  }

  public BucketService(BucketAdapterFactory bucketAdapterFactory) {
    this.bucketAdapterFactory = bucketAdapterFactory;
  }

  public void upload(String remote, byte[] file) {
    bucketAdapter().upload(remote, file);
  }

  public byte[] download(String local) {
    return bucketAdapter().download(local);
  }

  public void delete(String remote, boolean recursive) {
    bucketAdapter().delete(remote, recursive);
  }

  public List<String> list(String remote, boolean recursive) {
    return bucketAdapter().list(remote, recursive);
  }

  public String share(String remote, int expirationTime) {
    return bucketAdapter().share(remote, expirationTime);
  }
}
