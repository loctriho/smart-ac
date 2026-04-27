package com.smartac.device.service;

import com.smartac.config.DeviceIngestProperties;
import com.smartac.device.api.dto.BulkReadingsResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Short-lived in-memory deduplication for {@code Idempotency-Key}. Same key returns the same
 * status/body until TTL; not suitable for multi-instance deployments without a shared store.
 */
@Component
public class InMemoryIdempotencyStore {

  public record CachedOutcome(BulkReadingsResponse body, HttpStatus status, Instant expiresAt) {}

  private final DeviceIngestProperties props;
  private final ConcurrentHashMap<String, CachedOutcome> map = new ConcurrentHashMap<>();

  public InMemoryIdempotencyStore(DeviceIngestProperties props) {
    this.props = props;
  }

  public CachedOutcome get(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return null;
    }
    String key = rawKey.trim();
    CachedOutcome c = map.get(key);
    if (c == null) {
      return null;
    }
    Instant now = Instant.now();
    if (now.isAfter(c.expiresAt())) {
      map.remove(key, c);
      return null;
    }
    return c;
  }

  public void remember(String rawKey, HttpStatus status, BulkReadingsResponse body) {
    if (rawKey == null || rawKey.isBlank()) {
      return;
    }
    String key = rawKey.trim();
    Instant expires = Instant.now().plus(Duration.ofHours(props.getIdempotencyTtlHours()));
    map.put(key, new CachedOutcome(body, status, expires));
    int max = props.getIdempotencyMaxEntries();
    if (map.size() > max) {
      pruneExpired();
      evictIfOverCap();
    }
  }

  public void forget(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return;
    }
    map.remove(rawKey.trim());
  }

  private void pruneExpired() {
    Instant now = Instant.now();
    Iterator<Map.Entry<String, CachedOutcome>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, CachedOutcome> e = it.next();
      if (now.isAfter(e.getValue().expiresAt())) {
        it.remove();
      }
    }
  }

  /** Removes entries until size &le; max (one iterator pass; avoids O(n²) from re-walking the map). */
  private void evictIfOverCap() {
    int max = props.getIdempotencyMaxEntries();
    Iterator<Map.Entry<String, CachedOutcome>> it = map.entrySet().iterator();
    while (map.size() > max && it.hasNext()) {
      it.next();
      it.remove();
    }
  }
}
