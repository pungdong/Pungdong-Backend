package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 강사가 수강신청을 거절 → <b>학생</b>에게.
 *
 * <p>body 에 전액 환불 안내를 포함하므로 <b>별도 환불 완료 알림을 보내지 않는다</b>(2026-08-14 사용자 결정).
 */
@Value
@Builder
public class EnrollmentRejectedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
    String instructorNickName;
}
