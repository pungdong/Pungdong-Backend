package com.diving.pungdong.payment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.payment.dto.RefundQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 환불 — 학생이 수강을 종료(남은 회차 환불)한다. enrollment 도메인 경로지만 결제(토스 취소)라 payment 패키지에
 * 둔다(enrollment→payment 역참조 방지). 매처 {@code /enrollments/**} → authenticated.
 */
@RestController
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    /** 수강 환불신청 — 남은 회차 환불 견적+실행. 응답 = 회차별 환불 내역. */
    @PostMapping("/enrollments/{enrollmentId}/refund")
    public ResponseEntity<RefundQuote> refund(@CurrentUser Account account, @PathVariable Long enrollmentId) {
        return ResponseEntity.ok(refundService.refundEnrollment(account, enrollmentId));
    }
}
