package com.smartac.admin.service;

import com.smartac.admin.model.AdminUser;
import com.smartac.admin.repo.AdminUserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminManagementService {

  private final AdminUserRepository adminUserRepository;

  public AdminManagementService(AdminUserRepository adminUserRepository) {
    this.adminUserRepository = adminUserRepository;
  }

  public List<AdminUser> listAll() {
    return adminUserRepository.findAll();
  }

  @Transactional
  public void setBlocked(long targetId, boolean blocked, String currentAdminEmail) {
    AdminUser current =
        adminUserRepository
            .findByEmailIgnoreCase(currentAdminEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    AdminUser target =
        adminUserRepository
            .findById(targetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (target.getId().equals(current.getId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot block yourself");
    }
    target.setBlocked(blocked);
    adminUserRepository.save(target);
  }
}
