package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Reads the oldest unsent events with row-level pessimistic locks + SKIP LOCKED
     * so multiple pollers (HA deployment) never fight over the same rows.
     * Postgres specific; Hibernate translates LockModeType.PESSIMISTIC_WRITE + the
     * hint below into {@code FOR UPDATE SKIP LOCKED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.sentAt IS NULL
              AND o.attempts < :maxAttempts
            ORDER BY o.createdAt ASC
            """)
    List<OutboxEvent> lockUnsentBatch(@Param("maxAttempts") int maxAttempts,
                                      org.springframework.data.domain.Pageable pageable);
}
