package com.smartac.web.api;

import com.smartac.notification.NotificationResolutionService;
import com.smartac.notification.model.AdminNotification;
import com.smartac.notification.model.AdminNotification.NotificationType;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.Principal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationApiController {

  public record UnresolvedNotificationListResponse(
      List<AdminNotificationRepository.UnresolvedNotificationRow> notifications,
      int page,
      int size,
      boolean hasMore) {}

  private final AdminNotificationRepository notificationRepository;
  private final NotificationResolutionService resolutionService;

  public AdminNotificationApiController(
      AdminNotificationRepository notificationRepository,
      NotificationResolutionService resolutionService) {
    this.notificationRepository = notificationRepository;
    this.resolutionService = resolutionService;
  }

  @GetMapping("/open-count")
  public Map<String, Long> openCount() {
    Map<String, Long> m = new HashMap<>();
    m.put("count", notificationRepository.countByResolvedAndType(false, NotificationType.CO_THRESHOLD));
    return m;
  }

  @GetMapping("/unresolved")
  @Transactional(readOnly = true)
  public List<AdminNotification> unresolved() {
    return notificationRepository.findByResolvedAndTypeOrderByCreatedAtDesc(
        false, NotificationType.CO_THRESHOLD);
  }

  @GetMapping("/unresolved-page")
  @Transactional(readOnly = true)
  public UnresolvedNotificationListResponse unresolvedPage(
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "100") int size) {
    int safeSize = Math.max(1, Math.min(500, size));
    int safePage = Math.max(0, page);
    int offset = safePage * safeSize;

    List<AdminNotificationRepository.UnresolvedNotificationRow> rows =
        notificationRepository.findUnresolvedPageRows(offset, safeSize + 1);
    boolean hasMore = rows.size() > safeSize;
    if (hasMore) {
      rows = rows.subList(0, safeSize);
    }
    return new UnresolvedNotificationListResponse(rows, safePage, safeSize, hasMore);
  }

  @PostMapping("/{id}/resolve")
  public void resolve(@PathVariable long id, Principal principal) {
    resolutionService.resolve(id, principal.getName());
  }
}
