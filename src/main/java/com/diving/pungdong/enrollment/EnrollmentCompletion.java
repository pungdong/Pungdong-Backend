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
    private static long doneRegularRounds(Enrollment enrollment) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.isDone())
                .count();
    }

    /**
     * <b>자격증을 낼 수 있는 수강인가</b> — 정규 회차를 전부 이수했는가.
     *
     * <p>코스에 정규 회차가 0개면 완료로 보지 않는다(데이터 이상 — 0 == 0 으로 통과시키면 빈 코스가
     * 자격증 발급 근거가 된다).
     *
     * <p>⚠️ <b>hub 카드의 {@code COMPLETED} 와 같지 않다.</b> 정규를 다 끝낸 뒤 <b>추가세션(EXTRA)</b> 을
     * 잡으면 그 회차가 결제대기/수락대기라 카드 상태는 {@code PROGRESS} 로 돌아가지만, <b>자격증은 이미
     * 취득한 것</b>이라 등록을 막으면 안 된다. 두 질문이 실제로 다르므로 하나로 합치지 않는다 —
     * 대신 hub 응답이 이 값을 {@code certifiable} 로 <b>따로 노출</b>해서, FE 피커가 표시용 상태
     * ({@code status === 'COMPLETED'})를 대신 읽는 일이 없게 한다.
     */
    public static boolean isCertifiable(Enrollment enrollment) {
        int total = totalRegularRounds(enrollment);
        return total > 0 && doneRegularRounds(enrollment) >= total;
    }
}
