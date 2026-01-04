package com.bucketadapter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI api() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Bucket Adapter API")
                .version("v1")
                .description(
                    "Micro-service de gestion d’objets (upload, download, list, share…)."));
  }
}
