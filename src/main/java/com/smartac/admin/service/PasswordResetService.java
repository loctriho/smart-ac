package com.smartac.admin.service;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.model.PasswordResetToken;
import com.smartac.admin.repo.AdminUserRepository;
import com.smartac.admin.repo.PasswordResetTokenRepository;
import com.smartac.config.AppProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {

  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AdminUserRepository adminUserRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final AppProperties appProperties;

  public PasswordResetService(
      AdminUserRepository adminUserRepository,
      PasswordResetTokenRepository tokenRepository,
      PasswordEncoder passwordEncoder,
      AppProperties appProperties) {
    this.adminUserRepository = adminUserRepository;
    this.tokenRepository = tokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.appProperties = appProperties;
  }

  /** Idempotent: does not reveal whether the email exists. */
  @Transactional
  public void requestReset(String emailRaw) {
    if (emailRaw == null || emailRaw.isBlank()) {
      return;
    }
    String email = emailRaw.trim();
    adminUserRepository
        .findByEmailIgnoreCase(email)
        .ifPresent(
            admin -> {
              String token = newToken();
              PasswordResetToken t = new PasswordResetToken();
              t.setToken(token);
              t.setAdminUser(admin);
              t.setExpiresAt(
                  Instant.now()
                      .plus(appProperties.passwordResetTokenMinutes(), ChronoUnit.MINUTES));
              tokenRepository.save(t);
              String link = appProperties.baseUrl() + "/reset-password?token=" + token;
              if (appProperties.devLogPasswordResetLinks()) {
                log.info("Password reset link for {} : {}", email, link);
              }
            });
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    PasswordResetToken t =
        tokenRepository
            .findByToken(token)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token"));
    if (t.isUsed() || t.getExpiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token");
    }
    AdminUser admin = t.getAdminUser();
    admin.setPasswordHash(passwordEncoder.encode(newPassword));
    t.setUsedAt(Instant.now());
    adminUserRepository.save(admin);
    tokenRepository.save(t);
  }

  private static String newToken() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
