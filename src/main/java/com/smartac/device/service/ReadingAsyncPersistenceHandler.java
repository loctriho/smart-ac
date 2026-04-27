package com.smartac.device.service;

import com.smartac.admin.push.AdminPushService;
import com.smartac.config.DeviceIngestProperties;
import com.smartac.device.api.dto.SensorReadingPayload;
import com.smartac.device.model.Device;
import com.smartac.device.model.SensorReading;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.device.repo.SensorReadingRepository;
import com.smartac.notification.NotificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReadingAsyncPersistenceHandler {

  private final DeviceRepository deviceRepository;
  private final SensorReadingRepository sensorReadingRepository;
  private final NotificationService notificationService;
  private final DeviceIngestProperties ingestProperties;
  private final AdminPushService adminPushService;

  public ReadingAsyncPersistenceHandler(
      DeviceRepository deviceRepository,
      SensorReadingRepository sensorReadingRepository,
      NotificationService notificationService,
      DeviceIngestProperties ingestProperties,
      AdminPushService adminPushService) {
    this.deviceRepository = deviceRepository;
    this.sensorReadingRepository = sensorReadingRepository;
    this.notificationService = notificationService;
    this.ingestProperties = ingestProperties;
    this.adminPushService = adminPushService;
  }

  /** Loads the device by id, then {@link #persistBulk(Device, List)} (one transaction). */
  @Transactional
  public void persistBulk(long deviceId, List<SensorReadingPayload> payloads) {
    Device device =
        deviceRepository
            .findById(deviceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device"));
    persistBulk(device, payloads);
  }

  /**
   * Saves payloads as {@link SensorReading} rows. Skips work if {@code payloads} is null or empty.
   * When used from HTTP ingest, call inside the same transaction as the {@code device} load.
   */
  @Transactional
  public void persistBulk(Device device, List<SensorReadingPayload> payloads) {
    if (!device.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is disabled");
    }
    if (payloads == null || payloads.isEmpty()) {
      return;
    }

    List<SensorReading> rows = new ArrayList<>(payloads.size());
    for (SensorReadingPayload p : payloads) {
      rows.add(fromPayload(device, p));
    }

    int batch = Math.max(1, ingestProperties.getPersistBatchSize());
    int rowCount = rows.size();
    List<SensorReading> saved;
    if (rowCount <= batch) {
      saved = sensorReadingRepository.saveAll(rows);
    } else {
      saved = new ArrayList<>(rowCount);
      for (int i = 0; i < rowCount; i += batch) {
        int end = Math.min(i + batch, rowCount);
        saved.addAll(sensorReadingRepository.saveAll(rows.subList(i, end)));
      }
    }

    device.setLastIngestAt(Instant.now());
    deviceRepository.save(device);
    notificationService.onReadingsIngested(device, saved);
    adminPushService.notifyReadingsIngested(device.getId());
  }

  private static SensorReading fromPayload(Device device, SensorReadingPayload p) {
    SensorReading r = new SensorReading();
    r.setDevice(device);
    r.setRecordedAt(p.recordedAt());
    r.setTemperatureCelsius(p.temperatureCelsius());
    r.setHumidityPercent(p.humidityPercent());
    r.setCarbonMonoxidePpm(p.carbonMonoxidePpm());
    r.setHealthStatus(p.healthStatus().trim());
    return r;
  }
}
