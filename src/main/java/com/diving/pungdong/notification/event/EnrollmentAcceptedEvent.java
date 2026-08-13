package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 강사가 수강신청을 수락 → <b>학생</b>에게.
 *
 * <p>문구 조립에 필요한 값은 발행 시점에 담는다 — 리스너가 다시 조회하면 N+1 이고 트랜잭션 경계가 꼬인다.
 */
@Value
@Builder
public class EnrollmentAcceptedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
    String instructorNickName;
}
