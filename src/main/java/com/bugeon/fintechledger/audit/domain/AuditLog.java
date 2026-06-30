package com.bugeon.fintechledger.audit.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail record.
 *
 * Append-only: no UPDATE or DELETE is ever issued on this table.
 *
 * Written in Propagation.REQUIRES_NEW so the audit record is committed
 * even if the main business transaction rolls back (e.g. TRANSFER_FAILED).
 *
 * actor_id is nullable: pre-authentication actions (SIGNUP, LOGIN_FAILED)
 * have no authenticated actor at time of write.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
@ToString
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 255, updatable = false)
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 100, updatable = false)
    private String action;

    @Column(name = "target_type", length = 50, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10, updatable = false)
    private AuditResult result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "jsonb", updatable = false)
    private String context;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Factories ─────────────────────────────────────────────────────────────

    public static AuditLog success(AuditAction action,
                                   UUID actorId, String actorEmail,
                                   String targetType, UUID targetId,
                                   String contextJson,
                                   String ipAddress, String userAgent) {
        return build(action, AuditResult.SUCCESS, actorId, actorEmail,
                targetType, targetId, contextJson, ipAddress, userAgent);
    }

    public static AuditLog failure(AuditAction action,
                                   UUID actorId, String actorEmail,
                                   String targetType, UUID targetId,
                                   String contextJson,
                                   String ipAddress, String userAgent) {
        return build(action, AuditResult.FAILURE, actorId, actorEmail,
                targetType, targetId, contextJson, ipAddress, userAgent);
    }

    private static AuditLog build(AuditAction action, AuditResult result,
                                  UUID actorId, String actorEmail,
                                  String targetType, UUID targetId,
                                  String contextJson,
                                  String ipAddress, String userAgent) {
        AuditLog log = new AuditLog();
        log.action      = action.name();
        log.result      = result;
        log.actorId     = actorId;
        log.actorEmail  = actorEmail;
        log.targetType  = targetType;
        log.targetId    = targetId;
        log.context     = contextJson;
        log.ipAddress   = ipAddress;
        log.userAgent   = userAgent;
        log.createdAt   = Instant.now();
        return log;
    }
}
