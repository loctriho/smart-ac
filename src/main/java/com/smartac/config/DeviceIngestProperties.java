package com.smartac.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.device-ingest")
public class DeviceIngestProperties {

  /** Bounded queue for async bulk persistence ({@link DeviceIngestExecutorConfig}). */
  private int queueCapacity = 10000;

  private int workerThreads = 4;

  /**
   * Minimum seconds between accepted {@code POST /readings} requests per device (single or bulk).
   * Set to 0 to disable (tests / load tools).
   */
  private int readingsRateLimitSeconds = 60;

  private int maxSamplesPerRequest = 500;
  /** Hibernate/JDBC batch chunk size inside the persistence handler. */
  private int persistBatchSize = 100;

  private int idempotencyTtlHours = 24;
  private int idempotencyMaxEntries = 5000;

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }

  public int getWorkerThreads() {
    return workerThreads;
  }

  public void setWorkerThreads(int workerThreads) {
    this.workerThreads = workerThreads;
  }

  public int getReadingsRateLimitSeconds() {
    return readingsRateLimitSeconds;
  }

  public void setReadingsRateLimitSeconds(int readingsRateLimitSeconds) {
    this.readingsRateLimitSeconds = readingsRateLimitSeconds;
  }

  public int getMaxSamplesPerRequest() {
    return maxSamplesPerRequest;
  }

  public void setMaxSamplesPerRequest(int maxSamplesPerRequest) {
    this.maxSamplesPerRequest = maxSamplesPerRequest;
  }

  public int getPersistBatchSize() {
    return persistBatchSize;
  }

  public void setPersistBatchSize(int persistBatchSize) {
    this.persistBatchSize = persistBatchSize;
  }

  public int getIdempotencyTtlHours() {
    return idempotencyTtlHours;
  }

  public void setIdempotencyTtlHours(int idempotencyTtlHours) {
    this.idempotencyTtlHours = idempotencyTtlHours;
  }

  public int getIdempotencyMaxEntries() {
    return idempotencyMaxEntries;
  }

  public void setIdempotencyMaxEntries(int idempotencyMaxEntries) {
    this.idempotencyMaxEntries = idempotencyMaxEntries;
  }
}
