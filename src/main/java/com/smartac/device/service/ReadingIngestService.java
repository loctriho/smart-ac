package com.smartac.device.service;

import com.smartac.config.DeviceIngestProperties;
import com.smartac.device.api.dto.BulkReadingsIngestResult;
import com.smartac.device.api.dto.BulkReadingsRequest;
import com.smartac.device.api.dto.BulkReadingsResponse;
import com.smartac.device.api.dto.SensorReadingPayload;
import com.smartac.device.model.Device;
import com.smartac.device.repo.DeviceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReadingIngestService {

  private static final Logger log = LoggerFactory.getLogger(ReadingIngestService.class);

  private final DeviceRepository deviceRepository;
  private final DeviceIngestProperties ingestProperties;
  private final ReadingAsyncPersistenceHandler persistenceHandler;
  private final InMemoryIdempotencyStore idempotencyStore;
  private final DeviceReadingsRateLimiter rateLimiter;
  private final DeviceReadingsExecutor deviceReadingsExecutor;

  public ReadingIngestService(
      DeviceRepository deviceRepository,
      DeviceIngestProperties ingestProperties,
      ReadingAsyncPersistenceHandler persistenceHandler,
      InMemoryIdempotencyStore idempotencyStore,
      DeviceReadingsRateLimiter rateLimiter,
      @Qualifier("deviceReadingsExecutor") DeviceReadingsExecutor deviceReadingsExecutor) {
    this.deviceRepository = deviceRepository;
    this.ingestProperties = ingestProperties;
    this.persistenceHandler = persistenceHandler;
    this.idempotencyStore = idempotencyStore;
    this.rateLimiter = rateLimiter;
    this.deviceReadingsExecutor = deviceReadingsExecutor;
  }

  @Transactional
  public BulkReadingsIngestResult ingest(
      long deviceId, BulkReadingsRequest request, String idempotencyKeyRaw) {
    String idemKey = normalizeIdempotencyKey(idempotencyKeyRaw);
    if (idemKey != null) {
      var cached = idempotencyStore.get(idemKey);
      if (cached != null) {
        return new BulkReadingsIngestResult(cached.body(), cached.status());
      }
    }

    List<SensorReadingPayload> rawReadings = request.readings();
    if (rawReadings.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "readings must not be empty");
    }
    List<SensorReadingPayload> payloads = SensorReadingPayload.allWithUtcMinuteTimestamps(rawReadings);
    int n = payloads.size();
    int max = ingestProperties.getMaxSamplesPerRequest();
    if (n > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + max + " samples per request");
    }
    assertRecordedTimesNotInFuture(payloads);

    Device device =
        deviceRepository
            .findById(deviceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown device"));
    if (!device.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is disabled");
    }

    rateLimiter.recordAcceptanceOrThrow(deviceId);

    if (n > 1) {
      try {
        deviceReadingsExecutor.execute(() -> runPersistBulkAsync(deviceId, payloads, idemKey));
      } catch (RejectedExecutionException | ServiceOverloadedException e) {
        rateLimiter.forgetLastAcceptance(deviceId);
        if (e instanceof ServiceOverloadedException ex) {
          throw ex;
        }
        throw new ServiceOverloadedException("Ingest executor saturated; retry with backoff.", e);
      }
      var body = BulkReadingsResponse.async(n);
      remember(idemKey, HttpStatus.ACCEPTED, body);
      return new BulkReadingsIngestResult(body, HttpStatus.ACCEPTED);
    }

    try {
      persistenceHandler.persistBulk(device, payloads);
    } catch (RuntimeException e) {
      rateLimiter.forgetLastAcceptance(deviceId);
      throw e;
    }
    var body = BulkReadingsResponse.sync(n);
    remember(idemKey, HttpStatus.OK, body);
    return new BulkReadingsIngestResult(body, HttpStatus.OK);
  }

  private void runPersistBulkAsync(long deviceId, List<SensorReadingPayload> payloads, String idemKey) {
    try {
      persistenceHandler.persistBulk(deviceId, payloads);
    } catch (Exception e) {
      log.error("Async bulk persist failed for device {}", deviceId, e);
      if (idemKey != null) {
        idempotencyStore.forget(idemKey);
      }
    }
  }

  private void remember(String idemKey, HttpStatus status, BulkReadingsResponse body) {
    if (idemKey != null) {
      idempotencyStore.remember(idemKey, status, body);
    }
  }

  private static String normalizeIdempotencyKey(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }

  /**
   * Rejects samples whose UTC minute bucket is strictly after the server's current UTC minute so
   * devices cannot post future telemetry (sub-minute clock skew is tolerated).
   */
  private static void assertRecordedTimesNotInFuture(List<SensorReadingPayload> payloads) {
    Instant nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    for (int i = 0; i < payloads.size(); i++) {
      Instant t = payloads.get(i).recordedAt();
      if (t.isAfter(nowMinute)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "recordedAt must not be in a future UTC minute (index " + i + ")");
      }
    }
  }
}
