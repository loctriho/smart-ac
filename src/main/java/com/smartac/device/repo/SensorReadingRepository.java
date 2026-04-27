package com.smartac.device.repo;

import com.smartac.device.model.Device;
import com.smartac.device.model.SensorReading;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class SensorReadingRepository {

  @PersistenceContext private EntityManager em;

  /** Persists in order, flushes, returns the same instances (ids populated). */
  public List<SensorReading> saveAll(List<SensorReading> entities) {
    for (SensorReading r : entities) {
      em.persist(r);
    }
    em.flush();
    return entities;
  }

  public List<SensorReading> findRecentByDevice(Device device, int limit) {
    return em.createQuery(
            "SELECT r FROM SensorReading r WHERE r.device.id = :deviceId ORDER BY r.recordedAt DESC",
            SensorReading.class)
        .setParameter("deviceId", device.getId())
        .setMaxResults(limit)
        .getResultList();
  }

  public Optional<SensorReading> findTopByDeviceOrderByRecordedAtDesc(Device device) {
    List<SensorReading> list = findRecentByDevice(device, 1);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Returns at most 1 reading per device (latest by {@code recordedAt}) for the provided device ids.
   *
   * <p>This avoids N+1 queries for dashboard/device lists.
   */
  public List<SensorReading> findLatestByDeviceIds(Collection<Long> deviceIds) {
    if (deviceIds == null || deviceIds.isEmpty()) {
      return List.of();
    }
    // Deduplicate ids so IN (...) stays small and matches fewer duplicate work rows.
    Collection<Long> ids =
        deviceIds instanceof Set<?> || deviceIds.size() == 1
            ? deviceIds
            : Set.copyOf(deviceIds);
    // One row per device: ROW_NUMBER over (device_id) with tie-break on id (uses PK join).
    // Uses (device_id, recorded_at) index for the window partition scan.
    @SuppressWarnings("unchecked")
    List<SensorReading> rows =
        em.createNativeQuery(
                """
                SELECT r.id, r.device_id, r.recorded_at, r.temperature_celsius, r.humidity_percent,
                       r.carbon_monoxide_ppm, r.health_status
                FROM sensor_readings r
                INNER JOIN (
                  SELECT s.id
                  FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (
                             PARTITION BY device_id ORDER BY recorded_at DESC, id DESC) AS rn
                    FROM sensor_readings
                    WHERE device_id IN (:ids)
                  ) s
                  WHERE s.rn = 1
                ) pick ON r.id = pick.id
                """,
                SensorReading.class)
            .setParameter("ids", ids)
            .getResultList();
    return rows;
  }

  public List<SensorReading> findSeries(long deviceId, Instant from, Instant to) {
    return em.createQuery(
            "SELECT r FROM SensorReading r WHERE r.device.id = :deviceId AND r.recordedAt >= :from AND r.recordedAt <= :to ORDER BY r.recordedAt ASC",
            SensorReading.class)
        .setParameter("deviceId", deviceId)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();
  }
}
