package com.bucketadapter;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BucketAdapterFactory {

  private final Map<String, BucketAdapter> adapters;
  private final String provider;

  public BucketAdapterFactory(
      Map<String, BucketAdapter> adapters, @Value("${PROVIDER_IMPL}") String provider) {
    this.adapters = Map.copyOf(adapters);
    this.provider = provider;
  }

  public BucketAdapter getAdapter() {
    BucketAdapter adapter = adapters.get(provider);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "Unsupported provider: " + provider + ". Available: " + adapters.keySet());
    }
    return adapter;
  }
}
