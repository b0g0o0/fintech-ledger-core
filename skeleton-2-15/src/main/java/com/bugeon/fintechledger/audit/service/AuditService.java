package com.bugeon.fintechledger.audit.service;

import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.domain.AuditLog;
import com.bugeon.fintechledger.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 감사 로그 서비스.
 *
 * Propagation.REQUIRES_NEW: 비즈니스 트랜잭션이 롤백되어도 감사 로그는 커밋된다.
 * 실패한 거래도 반드시 추적해야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 성공한 거래 감사 로그.
     * REQUIRES_NEW: 별도 트랜잭션으로 즉시 커밋.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSuccess(AuditAction action,
                           UUID actorId, String actorEmail,
                           String targetType, UUID targetId,
                           String contextJson) {
        AuditLog log = AuditLog.success(action, actorId, actorEmail,
                targetType, targetId, contextJson, null, null);
        auditLogRepository.save(log);
        this.log.debug("Audit SUCCESS: action={} actor={} target={}/{}",
                action, actorId, targetType, targetId);
    }

    /**
     * 실패한 거래 감사 로그.
     * REQUIRES_NEW: 비즈니스 TX 롤백과 무관하게 커밋.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(AuditAction action,
                           UUID actorId, String actorEmail,
                           String targetType, UUID targetId,
                           String contextJson) {
        AuditLog auditLog = AuditLog.failure(action, actorId, actorEmail,
                targetType, targetId, contextJson, null, null);
        auditLogRepository.save(auditLog);
        log.debug("Audit FAILURE: action={} actor={}", action, actorId);
    }
}
