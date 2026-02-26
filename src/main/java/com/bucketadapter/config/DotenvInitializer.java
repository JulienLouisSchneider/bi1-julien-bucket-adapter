package com.bucketadapter.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Initializer to load environment variables from .env before Spring beans are instantiated. */
public class DotenvInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final Logger log = LoggerFactory.getLogger(DotenvInitializer.class);

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    Dotenv dotenv =
        Dotenv.configure()
            .ignoreIfMissing() // don't crash if .env is missing
            .load();

    dotenv
        .entries()
        .forEach(
            entry -> {
              String key = entry.getKey();
              String value = entry.getValue();

              // Set as system property for Spring
              System.setProperty(key, value);

              log.debug("Loaded env variable: {} -> {}", key, maskedValue(value, key));
            });

    log.info("Loaded {} environment variables from .env", dotenv.entries().size());
  }

  private String maskedValue(String value, String key) {
    if (key.contains("SECRET")
        || key.contains("KEY")
        || key.contains("PASSWORD")
        || key.contains("CREDENTIALS")) {
      if (value.length() > 8) {
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
      }
      return "***";
    }
    return value;
  }
}
