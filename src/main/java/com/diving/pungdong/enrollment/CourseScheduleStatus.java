package com.diving.pungdong.enrollment;

import java.util.Collection;
import java.util.List;

/**
 * 강의(=같은 course 의 회차들) 진행상태 — hub 카드 헤더용 파생값(저장 X).
 * 회차 상태들에서 **액션 우선**으로 파생한다(docs/features/student-schedule.md).
 *
 * <p>설계의 {@code finalizing/completed} 는 전 회차 완료·자격증 발급 추적이 BE 에 아직 없어 파생 불가(로드맵).
 * 정렬·필터에 쓰는 우선순위 = {@link #ORDER}.
 */
public enum CourseScheduleStatus {
    PAYMENT_DUE,   // 회차 중 결제 대기 있음 — 가장 먼저 액션 필요
    RESCHEDULING,  // 회차 중 강사 거절 있음 — 다른 일정 재신청 필요
    WAITING,       // 회차 중 강사 확인 중 있음
    PROGRESS,      // 확정만 — 별도 액션 없음
    CANCELLED;     // 전부 취소

    /** hub 정렬·필터 순서(액션 우선). */
    public static final List<CourseScheduleStatus> ORDER =
            List.of(PAYMENT_DUE, RESCHEDULING, WAITING, PROGRESS, CANCELLED);

    /** 회차 상태들에서 강의 상태 파생. 액션 우선순위로 가장 앞서는 것. */
    public static CourseScheduleStatus derive(Collection<RoundScheduleStatus> rounds) {
        if (rounds.contains(RoundScheduleStatus.PAYMENT_DUE)) return PAYMENT_DUE;
        if (rounds.contains(RoundScheduleStatus.RESCHEDULING)
                || rounds.contains(RoundScheduleStatus.REJECTED)) return RESCHEDULING; // 변경제안/거절 = 학생 액션
        if (rounds.contains(RoundScheduleStatus.WAITING))     return WAITING;
        if (rounds.contains(RoundScheduleStatus.CONFIRMED))   return PROGRESS;
        return CANCELLED;
    }
}
