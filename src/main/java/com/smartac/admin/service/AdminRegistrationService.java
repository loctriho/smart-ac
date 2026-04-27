package com.smartac.admin.service;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.model.Invitation;
import com.smartac.admin.repo.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRegistrationService {

  private final AdminUserRepository adminUserRepository;
  private final InvitationService invitationService;
  private final PasswordEncoder passwordEncoder;

  public AdminRegistrationService(
      AdminUserRepository adminUserRepository,
      InvitationService invitationService,
      PasswordEncoder passwordEncoder) {
    this.adminUserRepository = adminUserRepository;
    this.invitationService = invitationService;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public void register(String inviteToken, String email, String password) {
    Invitation inv;
    try {
      inv = invitationService.validateAndGet(inviteToken);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    String em = email.trim();
    if (adminUserRepository.findByEmailIgnoreCase(em).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
    }
    AdminUser u = new AdminUser();
    u.setEmail(em);
    u.setPasswordHash(passwordEncoder.encode(password));
    adminUserRepository.save(u);
    invitationService.consume(inv);
  }
}
