package com.smartac.admin.service;

import com.smartac.device.model.SensorReading;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.device.repo.SensorReadingRepository;
import com.smartac.web.TimeRange;
import com.smartac.web.TimeRangeResolver;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SensorSeriesService {

  public enum SensorChannel {
    temperature,
    humidity,
    co
  }

  private final DeviceRepository deviceRepository;
  private final SensorReadingRepository sensorReadingRepository;

  public SensorSeriesService(
      DeviceRepository deviceRepository, SensorReadingRepository sensorReadingRepository) {
    this.deviceRepository = deviceRepository;
    this.sensorReadingRepository = sensorReadingRepository;
  }

  public List<SeriesPoint> series(long deviceId, SensorChannel channel, TimeRange range) {
    if (!deviceRepository.existsById(deviceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
    }
    TimeRangeResolver.Range r = TimeRangeResolver.resolve(range);
    List<SensorReading> rows =
        sensorReadingRepository.findSeries(deviceId, r.from(), r.to());
    return rows.stream().map(row -> new SeriesPoint(row.getRecordedAt(), value(channel, row))).toList();
  }

  private static BigDecimal value(SensorChannel channel, SensorReading row) {
    return switch (channel) {
      case temperature -> row.getTemperatureCelsius();
      case humidity -> row.getHumidityPercent();
      case co -> row.getCarbonMonoxidePpm();
    };
  }

  public record SeriesPoint(java.time.Instant t, BigDecimal v) {}
}
