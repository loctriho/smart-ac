package com.smartac.device.repo;

import com.smartac.device.model.Device;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class DeviceRepository {

  @PersistenceContext private EntityManager em;

  public Optional<Device> findById(Long id) {
    return Optional.ofNullable(em.find(Device.class, id));
  }

  public Device save(Device device) {
    if (device.getId() == null) {
      em.persist(device);
      return device;
    }
    return em.merge(device);
  }

  public List<Device> findAll() {
    return em.createQuery("SELECT d FROM Device d ORDER BY d.id", Device.class).getResultList();
  }

  public List<Device> findPage(int offset, int limit) {
    int o = Math.max(0, offset);
    int l = Math.max(1, limit);
    return em.createQuery("SELECT d FROM Device d ORDER BY d.id", Device.class)
        .setFirstResult(o)
        .setMaxResults(l)
        .getResultList();
  }

  public List<Device> findAfterId(Long afterId, int limit) {
    int l = Math.max(1, limit);
    if (afterId == null) {
      return em.createQuery("SELECT d FROM Device d ORDER BY d.id", Device.class)
          .setMaxResults(l)
          .getResultList();
    }
    return em.createQuery("SELECT d FROM Device d WHERE d.id > :afterId ORDER BY d.id", Device.class)
        .setParameter("afterId", afterId)
        .setMaxResults(l)
        .getResultList();
  }

  public long count() {
    return em.createQuery("SELECT COUNT(d) FROM Device d", Long.class).getSingleResult();
  }

  public boolean existsById(long id) {
    List<Long> list =
        em.createQuery("SELECT d.id FROM Device d WHERE d.id = :id", Long.class)
            .setParameter("id", id)
            .setMaxResults(1)
            .getResultList();
    return !list.isEmpty();
  }

  public Optional<Device> findBySerialNumberIgnoreCase(String serialNumber) {
    String key = serialNormalized(serialNumber);
    if (key == null) {
      return Optional.empty();
    }
    List<Device> list =
        em.createQuery(
                "SELECT d FROM Device d WHERE d.serialNumberLower = :key", Device.class)
            .setParameter("key", key)
            .setMaxResults(1)
            .getResultList();
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public Optional<Device> findByApiTokenHash(String apiTokenHash) {
    List<Device> list =
        em.createQuery("SELECT d FROM Device d WHERE d.apiTokenHash = :h", Device.class)
            .setParameter("h", apiTokenHash)
            .setMaxResults(1)
            .getResultList();
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public boolean existsBySerialNumberIgnoreCase(String serial) {
    String key = serialNormalized(serial);
    if (key == null) {
      return false;
    }
    List<Long> list =
        em.createQuery("SELECT d.id FROM Device d WHERE d.serialNumberLower = :key", Long.class)
            .setParameter("key", key)
            .setMaxResults(1)
            .getResultList();
    return !list.isEmpty();
  }

  /** Trim + {@link Locale#ROOT} lowercase; matches persisted {@code serial_number_lower}. */
  private static String serialNormalized(String serial) {
    if (serial == null) {
      return null;
    }
    String t = serial.trim();
    return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
  }
}
