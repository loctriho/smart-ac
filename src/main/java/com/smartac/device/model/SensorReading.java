package com.smartac.device.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "sensor_readings",
    indexes = {
      @Index(name = "idx_sensor_reading_device_recorded", columnList = "device_id,recorded_at")
    })
public class SensorReading {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", nullable = false)
  private Device device;

  @Column(nullable = false)
  private Instant recordedAt;

  @Column(nullable = false, precision = 8, scale = 3)
  private BigDecimal temperatureCelsius;

  @Column(nullable = false, precision = 6, scale = 3)
  private BigDecimal humidityPercent;

  @Column(nullable = false, precision = 10, scale = 3)
  private BigDecimal carbonMonoxidePpm;

  @Column(nullable = false, length = 150)
  private String healthStatus;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Device getDevice() {
    return device;
  }

  public void setDevice(Device device) {
    this.device = device;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public void setRecordedAt(Instant recordedAt) {
    this.recordedAt = recordedAt;
  }

  public BigDecimal getTemperatureCelsius() {
    return temperatureCelsius;
  }

  public void setTemperatureCelsius(BigDecimal temperatureCelsius) {
    this.temperatureCelsius = temperatureCelsius;
  }

  public BigDecimal getHumidityPercent() {
    return humidityPercent;
  }

  public void setHumidityPercent(BigDecimal humidityPercent) {
    this.humidityPercent = humidityPercent;
  }

  public BigDecimal getCarbonMonoxidePpm() {
    return carbonMonoxidePpm;
  }

  public void setCarbonMonoxidePpm(BigDecimal carbonMonoxidePpm) {
    this.carbonMonoxidePpm = carbonMonoxidePpm;
  }

  public String getHealthStatus() {
    return healthStatus;
  }

  public void setHealthStatus(String healthStatus) {
    this.healthStatus = healthStatus;
  }
}
