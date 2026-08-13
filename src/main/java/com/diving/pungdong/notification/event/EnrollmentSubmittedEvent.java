package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 학생이 수강을 신청 → <b>강사</b>에게.
 *
 * <p>수신자는 항상 그 코스의 소유자({@code Course.instructor})다 — 그 신청을 수락/거절할 수 있는
 * 계정과 정의상 동일하다({@code requireForInstructor} 가 같은 값으로 권한을 검사한다).
 */
@Value
@Builder
public class EnrollmentSubmittedEvent {
    Long instructorAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
    String studentNickName;
}
