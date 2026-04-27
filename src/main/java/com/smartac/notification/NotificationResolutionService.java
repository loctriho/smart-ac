package com.smartac.notification;

import com.smartac.admin.push.AdminPushService;
import com.smartac.admin.model.AdminUser;
import com.smartac.admin.repo.AdminUserRepository;
import com.smartac.notification.model.AdminNotification;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationResolutionService {

  private final AdminNotificationRepository notificationRepository;
  private final AdminUserRepository adminUserRepository;
  private final AdminPushService adminPushService;

  public NotificationResolutionService(
      AdminNotificationRepository notificationRepository,
      AdminUserRepository adminUserRepository,
      AdminPushService adminPushService) {
    this.notificationRepository = notificationRepository;
    this.adminUserRepository = adminUserRepository;
    this.adminPushService = adminPushService;
  }

  @Transactional
  public void resolve(long notificationId, String resolverEmail) {
    AdminNotification n =
        notificationRepository
            .findById(notificationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (n.isResolved()) {
      return;
    }
    AdminUser admin =
        adminUserRepository
            .findByEmailIgnoreCase(resolverEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    n.setResolved(true);
    n.setResolvedAt(Instant.now());
    n.setResolvedBy(admin);
    notificationRepository.save(n);
    adminPushService.notifyNotificationsChanged();
  }
}
