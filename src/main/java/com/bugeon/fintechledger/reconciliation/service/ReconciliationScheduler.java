package com.bugeon.fintechledger.reconciliation.service;

import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation 정기 스케줄러.
 *
 * 매일 새벽 2시 UTC에 전체 계좌 정합성 검증을 실행한다.
 * (실서비스에서는 트래픽이 가장 낮은 시간대 선택)
 *
 * ShedLock 또는 Quartz 미적용 이유:
 *   단일 인스턴스 배포 기준 구현. 다중 인스턴스 배포 시
 *   ShedLock(@SchedulerLock)을 추가하여 중복 실행 방지 필요.
 *
 * 장애 시 동작:
 *   스케줄러 실패 시 다음 날 재실행. 중간 결과는 AuditLog에 기록됨.
 *   불일치 발견 시 ERROR 로그로 알람 시스템 연동 가능.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    /**
     * 매일 02:00 UTC 전체 정합성 검증.
     * cron = "초 분 시 일 월 요일"
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void runDailyReconciliation() {
        log.info("Daily reconciliation started");
        try {
            ReconciliationReport report = reconciliationService.runFullReconciliation();

            if (report.hasDiscrepancies()) {
                // 운영 알람 연동 지점 — PagerDuty, Slack, OpsGenie 등
                log.error("RECONCILIATION ALERT: {} discrepant account(s) found. " +
                                "Immediate investigation required.",
                        report.discrepantAccounts());
            } else {
                log.info("Daily reconciliation PASSED: {} accounts checked, all balanced.",
                        report.totalAccounts());
            }
        } catch (Exception e) {
            log.error("Daily reconciliation FAILED with exception — manual run required", e);
        }
    }
}
