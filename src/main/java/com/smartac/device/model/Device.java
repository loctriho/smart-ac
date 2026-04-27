package com.smartac.device.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * Case-insensitive serial uniqueness is enforced on {@link #serialNumberLower} (indexed). Keep
 * {@link #serialNumber} as the display value from registration.
 *
 * <p>If you upgrade a database that already had {@code devices} rows before this column existed,
 * add and backfill once before {@code NOT NULL}: {@code ALTER TABLE devices ADD COLUMN
 * serial_number_lower varchar(120) NULL;} then {@code UPDATE devices SET serial_number_lower =
 * LOWER(serial_number);} then set {@code NOT NULL} and add a unique index on {@code
 * serial_number_lower}.
 */
@Entity
@Table(
    name = "devices",
    indexes = {
      @Index(name = "uk_devices_serial_lower", columnList = "serial_number_lower", unique = true)
    })
public class Device {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 120)
  private String serialNumber;

  /**
   * {@code LOWER(serial_number)} for indexed lookups (maintained in {@link #onPrePersist()} /
   * {@link #onPreUpdate()}).
   */
  @Column(name = "serial_number_lower", nullable = false, length = 120)
  private String serialNumberLower;

  /** SHA-256 hex of the device API token (never store plaintext). */
  @Column(nullable = false, unique = true, length = 64)
  private String apiTokenHash;

  @Column(nullable = false, updatable = false)
  private Instant registrationDate;

  @Column(nullable = false, length = 64)
  private String firmwareVersion;

  @Column(nullable = false)
  private boolean enabled = true;

  /** Last successful ingest; used for steady-state rate limiting (single-sample uploads). */
  private Instant lastIngestAt;

  @PrePersist
  void onPrePersist() {
    if (registrationDate == null) {
      registrationDate = Instant.now();
    }
    syncSerialNumberLower();
  }

  @PreUpdate
  void onPreUpdate() {
    syncSerialNumberLower();
  }

  private void syncSerialNumberLower() {
    serialNumberLower =
        serialNumber == null ? null : serialNumber.toLowerCase(Locale.ROOT);
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getSerialNumber() {
    return serialNumber;
  }

  public void setSerialNumber(String serialNumber) {
    this.serialNumber = serialNumber;
    syncSerialNumberLower();
  }

  @JsonIgnore
  public String getSerialNumberLower() {
    return serialNumberLower;
  }

  @JsonIgnore
  public String getApiTokenHash() {
    return apiTokenHash;
  }

  public void setApiTokenHash(String apiTokenHash) {
    this.apiTokenHash = apiTokenHash;
  }

  public Instant getRegistrationDate() {
    return registrationDate;
  }

  public void setRegistrationDate(Instant registrationDate) {
    this.registrationDate = registrationDate;
  }

  public String getFirmwareVersion() {
    return firmwareVersion;
  }

  public void setFirmwareVersion(String firmwareVersion) {
    this.firmwareVersion = firmwareVersion;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Instant getLastIngestAt() {
    return lastIngestAt;
  }

  public void setLastIngestAt(Instant lastIngestAt) {
    this.lastIngestAt = lastIngestAt;
  }
}
