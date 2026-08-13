package com.diving.pungdong.enrollment;

import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.RoundKind;

/**
 * "이 수강은 끝났는가" 단일 판정 — <b>정규 회차가 전부 done</b> 일 때만 완료다.
 *
 * <p><b>왜 별도 클래스인가</b>: 이 판정을 두 곳이 쓴다. 강의일정 hub 가 카드 상태를
 * {@code COMPLETED} 로 내릴지({@link EnrollmentService}), 그리고 자격증 도메인이 "이 수강에
 * 자격증을 연결해도 되는가"를 검사할 때. 판정이 두 벌이면 <b>FE 가 피커에 띄운 강의를 BE 가
 * 400 으로 거절</b>하는 어긋남이 생긴다 — 화면과 검증이 같은 식을 봐야 한다.
 *
 * <p>⚠️ {@link CourseScheduleStatus#derive} 만으로는 부족하다. 그건 "잡힌 회차들"만 보므로
 * 3회차 중 1회차만 잡아 끝낸 수강도 {@code COMPLETED} 로 판정한다. 코스가 요구하는
 * <b>정규 회차 수</b>와 대조해야 진짜 완료다.
 */
public final class EnrollmentCompletion {

    private EnrollmentCompletion() {
    }

    /** 코스가 요구하는 정규 회차 수. 코스가 없으면 0. */
    public static int totalRegularRounds(Enrollment enrollment) {
        Course course = enrollment.getCourse();
        if (course == null) {
            return 0;
        }
        return (int) course.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                .count();
    }

    /** 실제로 done 처리된 정규 회차 수. */
    public static long doneRegularRounds(Enrollment enrollment) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.isDone())
                .count();
    }

    /**
     * 정규 회차를 전부 이수했는가. 코스에 정규 회차가 0개면 <b>완료로 보지 않는다</b>
     * (데이터 이상 — 0 == 0 으로 통과시키면 빈 코스가 자격증 발급 근거가 된다).
     */
    public static boolean isFullyCompleted(Enrollment enrollment) {
        int total = totalRegularRounds(enrollment);
        return total > 0 && doneRegularRounds(enrollment) >= total;
    }
}
