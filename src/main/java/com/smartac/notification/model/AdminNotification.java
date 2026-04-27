package com.smartac.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartac.admin.model.AdminUser;
import com.smartac.device.model.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "admin_notifications",
    indexes = {
      @Index(
          name = "idx_admin_notification_resolved_type_created",
          columnList = "resolved,type,created_at")
    })
public class AdminNotification {

  public enum NotificationType {
    CO_THRESHOLD,
    HEALTH_KEYWORD
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private NotificationType type;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", nullable = false)
  @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
  private Device device;

  @Column(length = 500)
  private String message;

  private BigDecimal coPpm;

  @Column(length = 64)
  private String healthKeyword;

  @Column(nullable = false)
  private boolean resolved = false;

  private Instant resolvedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resolved_by_id")
  @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
  private AdminUser resolvedBy;

  @Column(nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public NotificationType getType() {
    return type;
  }

  public void setType(NotificationType type) {
    this.type = type;
  }

  public Device getDevice() {
    return device;
  }

  public void setDevice(Device device) {
    this.device = device;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public BigDecimal getCoPpm() {
    return coPpm;
  }

  public void setCoPpm(BigDecimal coPpm) {
    this.coPpm = coPpm;
  }

  public String getHealthKeyword() {
    return healthKeyword;
  }

  public void setHealthKeyword(String healthKeyword) {
    this.healthKeyword = healthKeyword;
  }

  public boolean isResolved() {
    return resolved;
  }

  public void setResolved(boolean resolved) {
    this.resolved = resolved;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(Instant resolvedAt) {
    this.resolvedAt = resolvedAt;
  }

  public AdminUser getResolvedBy() {
    return resolvedBy;
  }

  public void setResolvedBy(AdminUser resolvedBy) {
    this.resolvedBy = resolvedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
