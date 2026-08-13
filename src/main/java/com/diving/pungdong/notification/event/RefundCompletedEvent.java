package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 환불 완료 → <b>학생</b>에게.
 *
 * <p>⚠️ <b>학생이 직접 요청한 환불에만</b> 발행한다. 거절·만료로 인한 <b>자동</b>환불은
 * {@code ENROLLMENT_REJECTED}/{@code ENROLLMENT_EXPIRED} body 가 이미 환불을 안내하므로,
 * 여기서도 쏘면 같은 사건으로 알림이 2건 연속 간다(2026-08-14 사용자 결정: 불필요).
 * 그래서 발행 지점은 {@code RefundService.refundEnrollment} 하나뿐이고
 * {@code refundRoundFully}/{@code refundRoundPartially}(이벤트 기반 자동 경로)에는 걸지 않는다.
 */
@Value
@Builder
public class RefundCompletedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    Long orderId;
    String courseTitle;
    int amount;
}
