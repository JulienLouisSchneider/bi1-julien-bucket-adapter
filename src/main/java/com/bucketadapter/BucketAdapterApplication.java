package com.bucketadapter;

import com.bucketadapter.config.DotenvInitializer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class BucketAdapterApplication {

  public static void main(String[] args) {
    new SpringApplicationBuilder(BucketAdapterApplication.class)
        .initializers(new DotenvInitializer())
        .run(args);
  }
}
