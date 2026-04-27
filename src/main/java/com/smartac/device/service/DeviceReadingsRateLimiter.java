package com.smartac.device.service;

import com.smartac.config.DeviceIngestProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Per-device minimum seconds between <em>accepted</em> {@code POST /readings} calls (see {@link
 * DeviceIngestProperties#getReadingsRateLimitSeconds()}). {@link #forgetLastAcceptance(long)} clears
 * the marker after a failed accept so the device is not blocked for the full interval.
 */
@Component
public class DeviceReadingsRateLimiter {

  private final DeviceIngestProperties props;
  private final ConcurrentHashMap<Long, Instant> lastAcceptedAt = new ConcurrentHashMap<>();

  public DeviceReadingsRateLimiter(DeviceIngestProperties props) {
    this.props = props;
  }

  /** Atomically records {@code now} for {@code deviceId} or throws if still inside the cooldown. */
  public void recordAcceptanceOrThrow(long deviceId) {
    int minSec = props.getReadingsRateLimitSeconds();
    if (minSec <= 0) {
      return;
    }
    Instant now = Instant.now();
    AtomicBoolean rejected = new AtomicBoolean(false);
    lastAcceptedAt.compute(
        deviceId,
        (id, prev) -> {
          if (prev != null && ChronoUnit.SECONDS.between(prev, now) < minSec) {
            rejected.set(true);
            return prev;
          }
          return now;
        });
    if (rejected.get()) {
      throw new RateLimitException(
          "At most one readings request every " + minSec + " seconds per device.");
    }
  }

  /** Clears the cooldown marker (e.g. executor rejected work or sync persist failed). */
  public void forgetLastAcceptance(long deviceId) {
    if (props.getReadingsRateLimitSeconds() > 0) {
      lastAcceptedAt.remove(deviceId);
    }
  }
}
