package com.smartac.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartac.admin.push.AdminPushService;
import com.smartac.device.model.Device;
import com.smartac.device.model.SensorReading;
import com.smartac.notification.model.AdminNotification;
import com.smartac.notification.model.AdminNotification.NotificationType;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

  @Mock private AdminNotificationRepository notificationRepository;
  @Mock private AdminPushService adminPushService;

  @InjectMocks private NotificationService notificationService;

  private Device device;

  @BeforeEach
  void setUp() {
    device = new Device();
    device.setId(42L);
    device.setSerialNumber("SN-42");
    when(notificationRepository.save(any(AdminNotification.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private static SensorReading reading(Instant at, BigDecimal co, String health) {
    SensorReading r = new SensorReading();
    r.setRecordedAt(at);
    r.setCarbonMonoxidePpm(co);
    r.setHealthStatus(health);
    r.setTemperatureCelsius(new BigDecimal("20"));
    r.setHumidityPercent(new BigDecimal("45"));
    r.setDevice(null);
    return r;
  }

  @Test
  void onReadingsIngested_empty_doesNotPersistOrPush() {
    notificationService.onReadingsIngested(device, List.of());
    verify(notificationRepository, never()).save(any());
    verify(adminPushService, never()).notifyNotificationsChanged();
  }

  @Test
  void coAboveThreshold_createsCoNotification() {
    notificationService.onReadingsIngested(
        device,
        List.of(
            reading(Instant.parse("2026-01-01T00:00:00Z"), new BigDecimal("10.1"), "ok"),
            reading(Instant.parse("2026-01-01T00:01:00Z"), new BigDecimal("2.0"), "ok")));
    ArgumentCaptor<AdminNotification> cap = ArgumentCaptor.forClass(AdminNotification.class);
    verify(notificationRepository).save(cap.capture());
    assertThat(cap.getValue().getType()).isEqualTo(NotificationType.CO_THRESHOLD);
    assertThat(cap.getValue().getCoPpm()).isEqualTo(new BigDecimal("10.1"));
    verify(adminPushService).notifyNotificationsChanged();
  }

  @Test
  void coAtNine_doesNotCreateCoNotification() {
    notificationService.onReadingsIngested(
        device, List.of(reading(Instant.parse("2026-01-01T00:00:00Z"), new BigDecimal("9.0"), "ok")));
    verify(notificationRepository, never()).save(any());
    verify(adminPushService, never()).notifyNotificationsChanged();
  }

  @Test
  void criticalHealthOnly_doesNotPersistOrPush() {
    notificationService.onReadingsIngested(
        device,
        List.of(
            reading(Instant.parse("2026-01-01T00:00:00Z"), new BigDecimal("1.0"), "needs_service")));
    verify(notificationRepository, never()).save(any());
    verify(adminPushService, never()).notifyNotificationsChanged();
  }

  @Test
  void duplicateCriticalHealthInBatch_stillNoNotifications() {
    notificationService.onReadingsIngested(
        device,
        List.of(
            reading(Instant.parse("2026-01-01T00:00:00Z"), new BigDecimal("1.0"), "gas_leak"),
            reading(Instant.parse("2026-01-01T00:01:00Z"), new BigDecimal("1.0"), "gas_leak")));
    verify(notificationRepository, never()).save(any());
    verify(adminPushService, never()).notifyNotificationsChanged();
  }

  @Test
  void coWithCriticalHealth_createsOnlyCoNotification() {
    notificationService.onReadingsIngested(
        device,
        List.of(
            reading(Instant.parse("2026-01-01T00:00:00Z"), new BigDecimal("10.0"), "needs_service")));
    ArgumentCaptor<AdminNotification> cap = ArgumentCaptor.forClass(AdminNotification.class);
    verify(notificationRepository).save(cap.capture());
    assertThat(cap.getValue().getType()).isEqualTo(NotificationType.CO_THRESHOLD);
    assertThat(cap.getValue().getCoPpm()).isEqualTo(new BigDecimal("10.0"));
    verify(adminPushService).notifyNotificationsChanged();
  }
}
