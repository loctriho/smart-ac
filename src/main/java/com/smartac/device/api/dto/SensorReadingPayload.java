package com.smartac.device.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record SensorReadingPayload(
    /**
     * Sample time in UTC; seconds and sub-second parts are ignored on ingest (stored and validated
     * at minute precision).
     */
    @NotNull Instant recordedAt,
    @NotNull @DecimalMin("-50.0") @DecimalMax("70.0") BigDecimal temperatureCelsius,
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal humidityPercent,
    @NotNull @DecimalMin("0.0") @DecimalMax("10000.0") BigDecimal carbonMonoxidePpm,
    @NotNull @Size(max = 150) String healthStatus) {

  /** Same payload with {@code recordedAt} truncated to the UTC minute (seconds and nanos cleared). */
  public SensorReadingPayload withRecordedAtUtcMinute() {
    return new SensorReadingPayload(
        recordedAt.truncatedTo(ChronoUnit.MINUTES),
        temperatureCelsius,
        humidityPercent,
        carbonMonoxidePpm,
        healthStatus);
  }

  /** Ingest path: normalize every sample to UTC minute precision before validation and persistence. */
  public static List<SensorReadingPayload> allWithUtcMinuteTimestamps(List<SensorReadingPayload> readings) {
    return readings.stream().map(SensorReadingPayload::withRecordedAtUtcMinute).toList();
  }
}
