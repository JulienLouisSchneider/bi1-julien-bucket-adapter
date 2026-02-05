package com.bucketadapter;

import com.bucketadapter.adapter.BucketAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BucketAdapterFactory {

  //TODO NGY Avoid using field injection -> method injection
  @Autowired private Map<String, BucketAdapter> adapters;

  public BucketAdapter getAdapter() {

    String provider = getConfig();

    BucketAdapter adapter = adapters.get(provider);
    if (adapter == null) {
      throw new IllegalArgumentException(
          "Unsupported provider: " + provider + ". Available: " + adapters.keySet());
    }
    return adapter;
  }

  private static String getConfig() {

    String value = System.getProperty("PROVIDER_IMPL");

    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          """
                      Type of provider\
                       is not configured.
                      When running locally: Add to .env file as \
                      PROVIDER_IMPL\
                      =value
                      When running in Docker: Set environment variable \
                      PROVIDER_IMPL""");
    }
    return value;
  }
}
