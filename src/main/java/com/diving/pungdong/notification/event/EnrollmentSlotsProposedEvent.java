package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 강사가 대안 일정을 제안 → <b>학생</b>에게.
 *
 * <p>제안에는 {@code proposalTtlHours}(6h) 만료가 걸려 있어 <b>지연이 곧 실패</b>다 — 알림이 늦거나
 * 안 가면 학생은 고를 기회를 잃고 hold 된 좌석만 묶인다.
 */
@Value
@Builder
public class EnrollmentSlotsProposedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
    String instructorNickName;
}
