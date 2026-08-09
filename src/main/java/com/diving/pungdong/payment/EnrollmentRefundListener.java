package com.diving.pungdong.payment;

import com.diving.pungdong.enrollment.event.EnrollmentPartialRefundRequestedEvent;
import com.diving.pungdong.enrollment.event.EnrollmentRefundRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 선결제 <b>자동환불</b> 수신부. enrollment 가 발행한 두 이벤트를 받아 환불한다:
 * {@link EnrollmentRefundRequestedEvent}(강사 거절 / 학생 취소 / 무응답 만료 → 전액) ·
 * {@link EnrollmentPartialRefundRequestedEvent}(더 싼 슬롯으로 일정 변경 → 차액).
 *
 * <p><b>의존 방향</b>: payment 가 enrollment(의 이벤트)를 import 한다 — 허용 방향(enrollment→payment 역참조 아님).
 * <p><b>동기</b>: 기본 {@code @EventListener} 는 발행자(reject/expiry) 트랜잭션 안에서 동기 실행 — 환불(PG 취소)이
 * 실패하면 예외가 전파돼 상태변경(REJECTED/CANCELLED)까지 롤백된다(환불 성공해야 상태도 커밋 = 돈-상태 원자성).
 */
@Component
@RequiredArgsConstructor
public class EnrollmentRefundListener {

    private final RefundService refundService;

    @EventListener
    public void onRefundRequested(EnrollmentRefundRequestedEvent event) {
        refundService.refundRoundFully(event.roundId(), event.reason());
    }

    @EventListener
    public void onPartialRefundRequested(EnrollmentPartialRefundRequestedEvent event) {
        refundService.refundRoundPartially(event.roundId(), event.amount(), event.reason());
    }
}
