package com.campus.studyroom.core;

import jakarta.persistence.*;
import org.springframework.stereotype.Repository;

/** PostgreSQL transaction lock serializes membership changes protecting the last administrator. */
@Repository
public class AdministrationLocks {
  @PersistenceContext EntityManager em;

  public void membership() {
    em.createNativeQuery(
            "select 1 from pg_advisory_xact_lock(hashtextextended('admin-membership',0))",
            Long.class)
        .getResultList();
  }

  public void changedRelation(Object parent) {
    em.lock(parent, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
  }
}
