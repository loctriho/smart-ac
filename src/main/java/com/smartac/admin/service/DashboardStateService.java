package com.smartac.admin.service;

import com.smartac.device.model.Device;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.device.repo.SensorReadingRepository;
import com.smartac.notification.model.AdminNotification.NotificationType;
import com.smartac.notification.repo.AdminNotificationRepository;
import com.smartac.web.api.dto.DashboardStateDto;
import com.smartac.web.api.dto.DeviceSensorSnapshotDto;
import com.smartac.web.api.dto.LastReadingDto;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardStateService {

  private static final Logger log = LoggerFactory.getLogger(DashboardStateService.class);

  private final DeviceRepository devices;
  private final SensorReadingRepository readings;
  private final AdminNotificationRepository notifications;
  private static final int DEFAULT_DASHBOARD_PAGE_SIZE = 100;
  private static final int MAX_DASHBOARD_PAGE_SIZE = 500;

  /**
   * Best-effort "latest reading" guardrails.
   *
   * <p>Under load, fetching latest readings for 100 devices can be expensive; we throttle concurrent
   * executions. (We no longer blank the whole dashboard on slow queries — that hid real data when
   * {@code sensor_readings} grew large.)
   */
  private static final Semaphore LATEST_READINGS_PERMITS = new Semaphore(4);
  /** Log when the bulk "latest per device" query exceeds this (ms). */
  private static final long LATEST_READINGS_SLOW_LOG_MS = 3000;

  public DashboardStateService(
      DeviceRepository devices,
      SensorReadingRepository readings,
      AdminNotificationRepository notifications) {
    this.devices = devices;
    this.readings = readings;
    this.notifications = notifications;
  }

  @Transactional(readOnly = true)
  public DashboardStateDto buildState() {
    return buildStateKeyset(null, DEFAULT_DASHBOARD_PAGE_SIZE, true);
  }

  @Transactional(readOnly = true)
  public DashboardStateDto buildState(int page, int size, boolean includeLatestReadings) {
    // Backward-compatible offset paging (used by tests/older UIs); prefer keyset for large fleets.
    long deviceCount = devices.count();
    int safeSize = Math.max(1, Math.min(MAX_DASHBOARD_PAGE_SIZE, size));
    int safePage = Math.max(0, page);
    int offset = safePage * safeSize;
    var sampleDevices = devices.findPage(offset, safeSize);
    return buildStateFromDeviceSlice(deviceCount, sampleDevices, includeLatestReadings);
  }

  @Transactional(readOnly = true)
  public DashboardStateDto buildStateKeyset(Long afterId, int size, boolean includeLatestReadings) {
    long deviceCount = devices.count();
    int safeSize = Math.max(1, Math.min(MAX_DASHBOARD_PAGE_SIZE, size));
    var sampleDevices = devices.findAfterId(afterId, safeSize);
    return buildStateFromDeviceSlice(deviceCount, sampleDevices, includeLatestReadings);
  }

  private DashboardStateDto buildStateFromDeviceSlice(
      long deviceCount, java.util.List<Device> sampleDevices, boolean includeLatestReadings) {
    var latest = includeLatestReadings ? maybeFetchLatest(sampleDevices) : Map.<Long, LastReadingDto>of();
    var snapshots = sampleDevices.stream().map(d -> snapshot(d, latest)).toList();
    return new DashboardStateDto(
        deviceCount, notifications.countByResolvedAndType(false, NotificationType.CO_THRESHOLD), snapshots);
  }

  private DeviceSensorSnapshotDto snapshot(Device d, Map<Long, LastReadingDto> latest) {
    return new DeviceSensorSnapshotDto(d.getId(), d.getSerialNumber(), latest.get(d.getId()));
  }

  private Map<Long, LastReadingDto> maybeFetchLatest(java.util.List<Device> sampleDevices) {
    if (sampleDevices == null || sampleDevices.isEmpty()) {
      return Map.of();
    }
    if (!LATEST_READINGS_PERMITS.tryAcquire()) {
      return Map.of();
    }
    long t0 = System.nanoTime();
    try {
      var ids = sampleDevices.stream().map(Device::getId).toList();
      var latestEntities =
          readings.findLatestByDeviceIds(ids).stream()
              .filter(r -> r.getDevice() != null && r.getDevice().getId() != null)
              .collect(
                  Collectors.toMap(
                      r -> r.getDevice().getId(),
                      Function.identity(),
                      (a, b) -> a));
      return latestEntities.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> LastReadingDto.from(e.getValue())));
    } finally {
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (ms > LATEST_READINGS_SLOW_LOG_MS) {
        log.warn(
            "findLatestByDeviceIds for {} devices took {} ms (consider DB maintenance or indexes)",
            sampleDevices.size(),
            ms);
      }
      LATEST_READINGS_PERMITS.release();
    }
  }
}
