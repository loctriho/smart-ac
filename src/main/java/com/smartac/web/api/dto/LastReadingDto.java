package com.smartac.web.api.dto;

import com.smartac.device.model.SensorReading;
import java.math.BigDecimal;
import java.time.Instant;

public record LastReadingDto(
    Instant recordedAt,
    BigDecimal temperatureCelsius,
    BigDecimal humidityPercent,
    BigDecimal carbonMonoxidePpm,
    String healthStatus) {

  public static LastReadingDto from(SensorReading r) {
    return new LastReadingDto(
        r.getRecordedAt(),
        r.getTemperatureCelsius(),
        r.getHumidityPercent(),
        r.getCarbonMonoxidePpm(),
        r.getHealthStatus());
  }
}
