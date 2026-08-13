package com.diving.pungdong.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 표면화 사양 — 결과 미확인 시도(승인 ATTEMPTED / 환불 REQUESTED)가 오래 남으면 세어 알린다.
 *
 * <p><b>왜 돈이 걸리나</b>: 이 행들은 "카드가 청구/취소됐는지 모르는" 상태라 사람이 PG 원장과 대사해야 하는데,
 * 지금까지 그런 행이 있다는 걸 자동으로 볼 방법이 없었다. 이 스윕이 그 공백을 메운다(탐지만 — 자동 재시도 X).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. RC* 대사.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentReconciliationTest {

    @Autowired
    private PaymentReconciliation reconciliation;
    @Autowired
    private PaymentApprovalJpaRepo approvalRepo;
    @Autowired
    private RefundOrderJpaRepo refundRepo;

    @AfterEach
    void cleanup() {
        approvalRepo.deleteAll();
        refundRepo.deleteAll();
    }

    @Test
    @DisplayName("RC1 유예 시간 넘게 미확정인 승인/환불 시도만 센다 — 방금 시작한 정상 시도는 오탐하지 않는다")
    void reportsOnlyStaleUnresolvedAttempts() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 오래된 미확정 — 대사 대상
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.ATTEMPTED)
                .attemptedAt(now.minusMinutes(20)).build());
        refundRepo.save(RefundOrder.builder()
                .amount(10000).reason("이전 시도").status(RefundStatus.REQUESTED)
                .createdAt(now.minusMinutes(20)).build());
        // 방금 시작한 미확정 — 곧 확정될 정상 시도라 세면 안 됨
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.ATTEMPTED)
                .attemptedAt(now).build());
        // 이미 확정된 시도 — 대사 대상 아님
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.APPROVED)
                .attemptedAt(now.minusMinutes(20)).resolvedAt(now.minusMinutes(19)).build());

        int stuck = reconciliation.reportStuck(now, 15);

        assertThat(stuck).isEqualTo(2); // 오래된 ATTEMPTED 1 + 오래된 REQUESTED 1
    }
}
