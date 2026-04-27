package com.smartac.admin.repo;

import com.smartac.admin.model.Invitation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class InvitationRepository {

  @PersistenceContext private EntityManager em;

  public Invitation save(Invitation invitation) {
    if (invitation.getId() == null) {
      em.persist(invitation);
      return invitation;
    }
    return em.merge(invitation);
  }

  public Optional<Invitation> findByToken(String token) {
    List<Invitation> list =
        em.createQuery("SELECT i FROM Invitation i WHERE i.token = :t", Invitation.class)
            .setParameter("t", token)
            .setMaxResults(1)
            .getResultList();
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }
}
