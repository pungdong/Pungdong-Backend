package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 결제 승인 완료 → <b>학생</b>에게.
 *
 * <p>돈이 계좌에서 나가는데 앱이 침묵하면 신뢰 문제가 된다. 카테고리는 {@code PAYMENT}(채널 payment)
 * — 앱에 이미 생성돼 있으나 아무도 쓰지 않던 채널이라 신설이 아니라 첫 사용이다.
 */
@Value
@Builder
public class PaymentCompletedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    Long orderId;
    String courseTitle;
    int amount;
}
