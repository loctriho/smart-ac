package com.smartac.notification;

import com.smartac.admin.push.AdminPushService;
import com.smartac.device.model.Device;
import com.smartac.device.model.SensorReading;
import com.smartac.notification.model.AdminNotification;
import com.smartac.notification.model.AdminNotification.NotificationType;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

  private static final BigDecimal CO_ALERT_THRESHOLD = new BigDecimal("9");

  private final AdminNotificationRepository notificationRepository;
  private final AdminPushService adminPushService;

  public NotificationService(
      AdminNotificationRepository notificationRepository, AdminPushService adminPushService) {
    this.notificationRepository = notificationRepository;
    this.adminPushService = adminPushService;
  }

  /**
   * Creates admin notifications from a bulk ingest:
   *
   * <p>One {@link NotificationType#CO_THRESHOLD} if any sample has CO strictly above 9 PPM (message
   * uses the max CO observed in the batch). Critical health keywords on readings are ignored for
   * notification purposes.
   */
  @Transactional
  public void onReadingsIngested(Device device, List<SensorReading> readings) {
    if (readings.isEmpty()) {
      return;
    }
    BigDecimal maxCo =
        readings.stream()
            .map(SensorReading::getCarbonMonoxidePpm)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    if (maxCo.compareTo(CO_ALERT_THRESHOLD) <= 0) {
      return;
    }
    AdminNotification n = new AdminNotification();
    n.setType(NotificationType.CO_THRESHOLD);
    n.setDevice(device);
    n.setCoPpm(maxCo);
    n.setMessage(
        "Carbon monoxide above 9 PPM (max observed: "
            + maxCo.stripTrailingZeros().toPlainString()
            + " PPM) on device "
            + device.getSerialNumber());
    notificationRepository.save(n);
    adminPushService.notifyNotificationsChanged();
  }
}
