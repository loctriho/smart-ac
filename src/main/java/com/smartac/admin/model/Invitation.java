package com.smartac.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "invitations")
public class Invitation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String token;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "created_by_id", nullable = false)
  private AdminUser createdBy;

  @Column(length = 255)
  private String emailHint;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant consumedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public AdminUser getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(AdminUser createdBy) {
    this.createdBy = createdBy;
  }

  public String getEmailHint() {
    return emailHint;
  }

  public void setEmailHint(String emailHint) {
    this.emailHint = emailHint;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public void setConsumedAt(Instant consumedAt) {
    this.consumedAt = consumedAt;
  }

  public boolean isConsumed() {
    return consumedAt != null;
  }

  public boolean isExpired(Instant now) {
    return expiresAt.isBefore(now);
  }
}
