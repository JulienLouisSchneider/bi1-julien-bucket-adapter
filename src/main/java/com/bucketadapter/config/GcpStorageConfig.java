package com.bucketadapter.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class GcpStorageConfig {

  @Bean
  public Storage gcsStorage() throws IOException {
    String projectId = getConfig("GOOGLE_CLOUD_PROJECT", "GCP project ID");
    String credsPath = getConfig("GOOGLE_APPLICATION_CREDENTIALS", "GCP credentials file");

    GoogleCredentials creds;
    if (!credsPath.isBlank()) {
      try (FileInputStream in = new FileInputStream(credsPath)) {
        creds = GoogleCredentials.fromStream(in);
      }
    } else {
      creds = GoogleCredentials.getApplicationDefault();
    }

    StorageOptions.Builder builder = StorageOptions.newBuilder().setCredentials(creds);

    // Le projectId peut aussi être auto-détecté ou fourni via GOOGLE_CLOUD_PROJECT
    if (!projectId.isBlank()) {
      builder.setProjectId(projectId);
    }

    return StorageOptions.newBuilder()
        .setCredentials(creds)
        .setProjectId(projectId)
        .build()
        .getService();
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
