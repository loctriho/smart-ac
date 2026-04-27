package com.smartac.web.api;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.service.AdminManagementService;
import com.smartac.web.api.dto.AdminUserResponseDto;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/admins")
public class AdminAdminsApiController {

  private final AdminManagementService adminManagementService;

  public AdminAdminsApiController(AdminManagementService adminManagementService) {
    this.adminManagementService = adminManagementService;
  }

  @GetMapping
  public List<AdminUserResponseDto> list() {
    return adminManagementService.listAll().stream().map(AdminAdminsApiController::toDto).toList();
  }

  @PostMapping("/{id}/blocked")
  public void setBlocked(
      @PathVariable long id, @RequestParam boolean blocked, Principal principal) {
    adminManagementService.setBlocked(id, blocked, principal.getName());
  }

  private static AdminUserResponseDto toDto(AdminUser u) {
    return new AdminUserResponseDto(
        u.getId(), u.getEmail(), u.isEnabled(), u.isBlocked(), u.getCreatedAt());
  }
}
