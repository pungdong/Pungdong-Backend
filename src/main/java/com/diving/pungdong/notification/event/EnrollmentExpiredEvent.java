package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * TTL 만료로 수강신청이 자동 취소 → <b>학생</b>에게.
 *
 * <p>통보 없이 신청이 사라지는 걸 막는 게 목적이다. 만료는 두 갈래이고 {@code paid} 가 이를 가른다:
 * <ul>
 *   <li>{@code false} — 미결제 12h 만료. 환불 없음.</li>
 *   <li>{@code true} — 결제완료 무응답 24h 만료. <b>전액 자동환불</b>이 함께 일어나므로 body 에
 *       환불 안내를 포함하고, 별도 환불 완료 알림은 보내지 않는다(2026-08-14 사용자 결정).</li>
 * </ul>
 */
@Value
@Builder
public class EnrollmentExpiredEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
    String instructorNickName;
    /** 결제완료(ACCEPT_PENDING) 건이었나 = 자동환불 대상이었나. */
    boolean paid;
}
