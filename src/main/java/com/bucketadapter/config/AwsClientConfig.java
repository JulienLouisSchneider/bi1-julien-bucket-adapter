package com.bucketadapter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsClientConfig {


    @Lazy
    @Bean(destroyMethod = "close")
    public S3Client s3Client() {

        String region = resolveRegion();
        String accessKey = getConfig("AWS_ACCESS_KEY_ID", "AWS Access Key ID");
        String secretKey = getConfig("AWS_SECRET_ACCESS_KEY", "AWS Secret Access Key");

        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();
    }

    /**
     * Resolve AWS S3 region from system property or environment variable.
     *
     * @return AWS S3 region
     */
    private static String resolveRegion() {
        return getConfig(
                "AWS_REGION",
                "S3 region");
    }

    /**
     * Utility method to get configuration from system property or environment
     * variable.
     *
     * @param envVar     - environment variable name
     * @return configuration value
     */
    private static String getConfig(String envVar, String configName) {

        String value = System.getProperty(envVar);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    configName + " is not configured.\n" +
                            "When running locally: Add to .env file as " + envVar + "=value\n" +
                            "When running in Docker: Set environment variable " + envVar);
        }
        return value;
    }
}