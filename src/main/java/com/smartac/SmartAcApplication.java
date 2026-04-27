package com.smartac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = {JpaRepositoriesAutoConfiguration.class})
@ConfigurationPropertiesScan
public class SmartAcApplication {

  public static void main(String[] args) {
    SpringApplication.run(SmartAcApplication.class, args);
  }
}
