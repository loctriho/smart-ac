package com.smartac.admin.repo;

import com.smartac.admin.model.PasswordResetToken;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PasswordResetTokenRepository {

  @PersistenceContext private EntityManager em;

  public PasswordResetToken save(PasswordResetToken token) {
    if (token.getId() == null) {
      em.persist(token);
      return token;
    }
    return em.merge(token);
  }

  public Optional<PasswordResetToken> findByToken(String token) {
    List<PasswordResetToken> list =
        em.createQuery("SELECT t FROM PasswordResetToken t WHERE t.token = :tok", PasswordResetToken.class)
            .setParameter("tok", token)
            .setMaxResults(1)
            .getResultList();
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }
}
