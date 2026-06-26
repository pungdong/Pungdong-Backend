package com.diving.pungdong.enrollment;

/**
 * 회차(=enrollment 1건) 진행상태 — 수강생 강의일정 hub 표시용 파생값(저장 X).
 * BE {@link EnrollmentStatus}(5값)를 설계 회차 어휘로 매핑한다(docs/features/student-schedule.md 매핑표).
 *
 * <p>설계의 {@code done/changing/locked/payment_expired} 는 출석·일정변경·다회차·만료 추적이 BE 에
 * 아직 없어 매핑 대상이 아니다(로드맵).
 */
public enum RoundScheduleStatus {
    WAITING,      // PENDING — 강사 확인 중
    PAYMENT_DUE,  // PAYMENT_PENDING — 결제 필요(수락됨)
    CONFIRMED,    // CONFIRMED — 확정(결제 완료)
    REJECTED,     // REJECTED — 강사 거절(복구 가능)
    CANCELLED;    // CANCELLED — 학생 취소

    public static RoundScheduleStatus from(EnrollmentStatus status) {
        switch (status) {
            case PENDING:         return WAITING;
            case PAYMENT_PENDING: return PAYMENT_DUE;
            case CONFIRMED:       return CONFIRMED;
            case REJECTED:        return REJECTED;
            case CANCELLED:
            default:              return CANCELLED;
        }
    }
}
