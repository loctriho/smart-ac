package com.smartac.notification.repo;

import com.smartac.notification.model.AdminNotification;
import com.smartac.notification.model.AdminNotification.NotificationType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AdminNotificationRepository {

  @PersistenceContext private EntityManager em;

  public record UnresolvedNotificationRow(
      long id, Instant createdAt, String type, String deviceSerialNumber, String message) {}


  public long count() {
    return em.createQuery("SELECT COUNT(n) FROM AdminNotification n", Long.class)
        .getSingleResult();
  }

  /** Count notifications matching resolution state and alert type (e.g. open CO-only for admin UI). */
  public long countByResolvedAndType(boolean resolved, NotificationType type) {
    return em.createQuery(
            "SELECT COUNT(n) FROM AdminNotification n WHERE n.resolved = :r AND n.type = :t",
            Long.class)
        .setParameter("r", resolved)
        .setParameter("t", type)
        .getSingleResult();
  }

  /** Unresolved list filtered by type (admin UI shows CO alerts only). */
  public List<AdminNotification> findByResolvedAndTypeOrderByCreatedAtDesc(
      boolean resolved, NotificationType type) {
    return em.createQuery(
            "SELECT n FROM AdminNotification n WHERE n.resolved = :r AND n.type = :t ORDER BY n.createdAt DESC",
            AdminNotification.class)
        .setParameter("r", resolved)
        .setParameter("t", type)
        .getResultList();
  }

  /**
   * Fast unresolved notifications page.
   *
   * <p>Returns {@code limit + 1} rows so callers can compute {@code hasMore} without COUNT(*).
   * Uses joins to avoid per-row lazy loads during JSON serialization.
   */
  public List<UnresolvedNotificationRow> findUnresolvedPageRows(int offset, int limitPlusOne) {
    int o = Math.max(0, offset);
    int l = Math.max(1, limitPlusOne);
    List<Object[]> rows =
        em.createQuery(
                """
                SELECT n.id, n.createdAt, n.type, d.serialNumber, n.message
                FROM AdminNotification n
                JOIN n.device d
                WHERE n.resolved = false AND n.type = :alertType
                ORDER BY n.createdAt DESC
                """,
                Object[].class)
            .setParameter("alertType", NotificationType.CO_THRESHOLD)
            .setFirstResult(o)
            .setMaxResults(l)
            .getResultList();
    ArrayList<UnresolvedNotificationRow> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(
          new UnresolvedNotificationRow(
              ((Number) r[0]).longValue(),
              (Instant) r[1],
              r[2] == null ? null : String.valueOf(r[2]),
              r[3] == null ? null : String.valueOf(r[3]),
              r[4] == null ? null : String.valueOf(r[4])));
    }
    return out;
  }

  public AdminNotification save(AdminNotification n) {
    if (n.getId() == null) {
      em.persist(n);
      return n;
    }
    return em.merge(n);
  }

  public Optional<AdminNotification> findById(Long id) {
    return Optional.ofNullable(em.find(AdminNotification.class, id));
  }
}
