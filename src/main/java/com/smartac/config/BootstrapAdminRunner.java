package com.smartac.config;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.repo.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BootstrapAdminRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  @Bean
  CommandLineRunner bootstrapAdmin(
      AdminUserRepository adminUserRepository,
      PasswordEncoder passwordEncoder,
      AppProperties appProperties) {
    return args -> {
      if (adminUserRepository.count() > 0) {
        return;
      }
      if (appProperties.bootstrap() == null) {
        log.warn("Skipping bootstrap admin: app.bootstrap is not configured.");
        return;
      }
      String email = appProperties.bootstrap().email();
      String raw = appProperties.bootstrap().password();
      AdminUser u = new AdminUser();
      u.setEmail(email);
      u.setPasswordHash(passwordEncoder.encode(raw));
      adminUserRepository.save(u);
      log.warn(
          "Created bootstrap admin user {} (change password in production).", email);
    };
  }
}
