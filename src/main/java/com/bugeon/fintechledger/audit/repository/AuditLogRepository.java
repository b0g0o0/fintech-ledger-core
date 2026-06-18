package com.bugeon.fintechledger.audit.repository;

import com.bugeon.fintechledger.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findAllByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);

    Page<AuditLog> findAllByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    Page<AuditLog> findAllByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, UUID targetId, Pageable pageable);

    /**
     * Flexible admin search — all parameters are optional.
     * Null-safe via IS NULL OR comparisons.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:actorId IS NULL OR a.actorId = :actorId)
          AND (:action   IS NULL OR a.action  = :action)
          AND (:from     IS NULL OR a.createdAt >= :from)
          AND (:to       IS NULL OR a.createdAt <= :to)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLog> search(@Param("actorId") UUID actorId,
                          @Param("action")  String action,
                          @Param("from")    Instant from,
                          @Param("to")      Instant to,
                          Pageable pageable);
}
