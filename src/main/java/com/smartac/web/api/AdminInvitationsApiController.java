package com.smartac.web.api;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.repo.AdminUserRepository;
import com.smartac.admin.service.InvitationService;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/invitations")
public class AdminInvitationsApiController {

  private final InvitationService invitationService;
  private final AdminUserRepository adminUserRepository;

  public AdminInvitationsApiController(
      InvitationService invitationService, AdminUserRepository adminUserRepository) {
    this.invitationService = invitationService;
    this.adminUserRepository = adminUserRepository;
  }

  public record CreateInvitationRequest(String emailHint) {}

  @PostMapping
  public Map<String, String> create(
      @RequestBody(required = false) CreateInvitationRequest body, Principal principal) {
    AdminUser creator =
        adminUserRepository.findByEmailIgnoreCase(principal.getName()).orElseThrow();
    String hint = body != null ? body.emailHint() : null;
    String link = invitationService.createInvitation(creator, hint);
    return Map.of("inviteLink", link);
  }
}
