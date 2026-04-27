package com.smartac.admin.service;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.model.Invitation;
import com.smartac.admin.repo.InvitationRepository;
import com.smartac.config.AppProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final InvitationRepository invitationRepository;
  private final AppProperties appProperties;

  public InvitationService(InvitationRepository invitationRepository, AppProperties appProperties) {
    this.invitationRepository = invitationRepository;
    this.appProperties = appProperties;
  }

  @Transactional
  public String createInvitation(AdminUser creator, String emailHint) {
    String token = newToken();
    Invitation inv = new Invitation();
    inv.setToken(token);
    inv.setCreatedBy(creator);
    inv.setEmailHint(emailHint != null && !emailHint.isBlank() ? emailHint.trim() : null);
    inv.setExpiresAt(
        Instant.now().plus(appProperties.invitationExpiryHours(), ChronoUnit.HOURS));
    invitationRepository.save(inv);
    return appProperties.baseUrl() + "/register?token=" + token;
  }

  public Invitation validateAndGet(String token) {
    Invitation inv =
        invitationRepository
            .findByToken(token)
            .orElseThrow(() -> new IllegalArgumentException("Invalid invitation"));
    if (inv.isConsumed()) {
      throw new IllegalArgumentException("Invitation already used");
    }
    if (inv.isExpired(Instant.now())) {
      throw new IllegalArgumentException("Invitation expired");
    }
    return inv;
  }

  @Transactional
  public void consume(Invitation inv) {
    inv.setConsumedAt(Instant.now());
    invitationRepository.save(inv);
  }

  private static String newToken() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
