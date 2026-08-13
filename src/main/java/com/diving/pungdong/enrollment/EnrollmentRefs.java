package com.diving.pungdong.enrollment;

import lombok.Getter;

/**
 * 회차 하나에서 알림 발행에 필요한 좌표를 안전하게 뽑아낸다 — 수강신청/만료/완료 세 곳이 공유한다.
 *
 * <p><b>널 안전이 핵심이다.</b> 알림 리스너는 {@code MANDATORY} 전파라 <b>비즈니스 트랜잭션에
 * 합류</b>한다. 수신자 id 가 null 인 채로 발행하면 알림함/outbox 의 {@code NOT NULL} 위반이 나고
 * <b>수강신청·수락 같은 본 작업까지 롤백</b>된다. 알림은 부수효과이므로 그렇게 되면 안 된다 —
 * 좌표를 못 만들면 {@link #canNotifyStudent()} 등이 false 를 돌려 <b>발행을 건너뛴다</b>.
 *
 * <p>(실제로 {@code InstructorEnrollmentService.requireForInstructor} 가
 * {@code course.getInstructor() == null} 을 방어하고 있다 = 소유자가 비어 있는 데이터가 존재한다.)
 */
@Getter
public final class EnrollmentRefs {

    private final Long studentAccountId;
    private final Long instructorAccountId;
    private final Long courseId;
    private final Long enrollmentId;
    private final Long roundId;
    private final String courseTitle;

    private EnrollmentRefs(Long studentAccountId, Long instructorAccountId, Long courseId,
                           Long enrollmentId, Long roundId, String courseTitle) {
        this.studentAccountId = studentAccountId;
        this.instructorAccountId = instructorAccountId;
        this.courseId = courseId;
        this.enrollmentId = enrollmentId;
        this.roundId = roundId;
        this.courseTitle = courseTitle;
    }

    public static EnrollmentRefs of(EnrollmentRound round) {
        if (round == null) {
            return new EnrollmentRefs(null, null, null, null, null, null);
        }
        Enrollment enrollment = round.getEnrollment();
        var course = enrollment == null ? null : enrollment.getCourse();
        var student = enrollment == null ? null : enrollment.getStudent();
        var instructor = course == null ? null : course.getInstructor();
        return new EnrollmentRefs(
                student == null ? null : student.getId(),
                instructor == null ? null : instructor.getId(),
                course == null ? null : course.getId(),
                enrollment == null ? null : enrollment.getId(),
                round.getId(),
                course == null ? null : course.getTitle());
    }

    /** 학생에게 보낼 수 있나 (수신자 id 가 있나). */
    public boolean canNotifyStudent() {
        return studentAccountId != null;
    }

    /** 강사에게 보낼 수 있나. */
    public boolean canNotifyInstructor() {
        return instructorAccountId != null;
    }

    /** 문구에 쓰는 코스명 — 없으면 빈 문자열이 아니라 무난한 대체어(문구가 "null 수업" 이 되면 안 된다). */
    public String courseTitleOrFallback() {
        return courseTitle == null || courseTitle.isBlank() ? "수업" : courseTitle;
    }
}
