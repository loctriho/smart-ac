package com.smartac.admin.repo;

import com.smartac.admin.model.AdminUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AdminUserRepository {

  @PersistenceContext private EntityManager em;

  public long count() {
    return em.createQuery("SELECT COUNT(a) FROM AdminUser a", Long.class).getSingleResult();
  }

  public AdminUser save(AdminUser user) {
    if (user.getId() == null) {
      em.persist(user);
      return user;
    }
    return em.merge(user);
  }

  public Optional<AdminUser> findByEmailIgnoreCase(String email) {
    List<AdminUser> list =
        em.createQuery(
                "SELECT a FROM AdminUser a WHERE LOWER(a.email) = LOWER(:email)", AdminUser.class)
            .setParameter("email", email)
            .setMaxResults(1)
            .getResultList();
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public boolean existsByEmailIgnoreCase(String email) {
    List<Long> list =
        em.createQuery("SELECT a.id FROM AdminUser a WHERE LOWER(a.email) = LOWER(:email)", Long.class)
            .setParameter("email", email)
            .setMaxResults(1)
            .getResultList();
    return !list.isEmpty();
  }

  public List<AdminUser> findAll() {
    return em.createQuery("SELECT a FROM AdminUser a ORDER BY a.id", AdminUser.class)
        .getResultList();
  }

  public Optional<AdminUser> findById(Long id) {
    return Optional.ofNullable(em.find(AdminUser.class, id));
  }
}
