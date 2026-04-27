package com.smartac.web.api;

import com.smartac.device.api.dto.SensorReadingPayload;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.device.service.ReadingAsyncPersistenceHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists a small synthetic bulk so {@link com.smartac.notification.NotificationService} creates
 * the same admin notifications as real device traffic (CO strictly above 9 PPM only).
 */
@RestController
@RequestMapping("/api/admin/devices")
public class AdminDeviceNotificationSimulationController {

  private final DeviceRepository deviceRepository;
  private final ReadingAsyncPersistenceHandler persistenceHandler;

  public AdminDeviceNotificationSimulationController(
      DeviceRepository deviceRepository, ReadingAsyncPersistenceHandler persistenceHandler) {
    this.deviceRepository = deviceRepository;
    this.persistenceHandler = persistenceHandler;
  }

  /**
   * Creates one CO-threshold notification when max CO in the batch is above 9 PPM (same rule as
   * production ingest). Health status values on synthetic rows are for realistic data only.
   */
  @PostMapping("/{deviceId}/simulate-notifications")
  public Map<String, Object> simulateNotifications(@PathVariable long deviceId) {
    if (!deviceRepository.existsById(deviceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
    }
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    List<SensorReadingPayload> rows = new ArrayList<>();
    rows.add(row(t0, new BigDecimal("10.5"), "ok"));
    rows.add(row(t0.plus(1, ChronoUnit.MINUTES), new BigDecimal("2"), "needs_service"));
    rows.add(row(t0.plus(2, ChronoUnit.MINUTES), new BigDecimal("2"), "needs_new_filter"));
    rows.add(row(t0.plus(3, ChronoUnit.MINUTES), new BigDecimal("2"), "gas_leak"));
    persistenceHandler.persistBulk(deviceId, rows);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ingestedSamples", rows.size());
    body.put(
        "message",
        "Synthetic readings persisted; admins should see a CO-threshold notification if max CO in the batch is above 9 PPM.");
    return body;
  }

  private static SensorReadingPayload row(Instant recordedAt, BigDecimal coPpm, String health) {
    return new SensorReadingPayload(
        recordedAt,
        new BigDecimal("22.0"),
        new BigDecimal("45.0"),
        coPpm,
        health);
  }
}
