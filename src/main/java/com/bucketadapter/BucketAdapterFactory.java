package com.bucketadapter;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BucketAdapterFactory {

  @Autowired private Map<String, BucketAdapter> adapters;

  private String provider;

  public BucketAdapter getAdapter() {

    provider = getConfig("PROVIDER_IMPL", "Type of provider");

    BucketAdapter adapter = adapters.get(provider);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "Unsupported provider: " + provider + ". Available: " + adapters.keySet());
    }
    return adapter;
  }

  private static String getConfig(String envVar, String configName) {

    String value = System.getProperty(envVar);

    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          configName
              + " is not configured.\n"
              + "When running locally: Add to .env file as "
              + envVar
              + "=value\n"
              + "When running in Docker: Set environment variable "
              + envVar);
    }
    return value;
  }
}
