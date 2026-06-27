package com.diving.pungdong.enrollment;

/**
 * 회차(=EnrollmentRound 1건) 진행상태 — 수강생 강의일정 hub 표시용 파생값(저장 X).
 * BE {@link EnrollmentStatus} + 일정변경 제안(proposedDates)을 설계 회차 어휘로 매핑한다.
 *
 * <p>아직 잡지 않은 미래 회차(locked)는 hub 가 {@code rounds[]} 에 안 넣고 {@code totalRounds}/{@code nextRoundIndex}
 * 로 내려, FE 가 placeholder 를 그린다. 설계의 {@code done/payment_expired} 는 출석/만료 추적 후속이라 미매핑.
 */
public enum RoundScheduleStatus {
    WAITING,       // PENDING(제안 없음) — 강사 확인 중
    RESCHEDULING,  // PENDING + 강사 일정변경 제안 — 학생이 제안 날짜 선택 대기
    PAYMENT_DUE,   // PAYMENT_PENDING — 결제 필요(수락됨)
    CONFIRMED,     // CONFIRMED — 확정(결제 완료)
    REJECTED,      // REJECTED — 강사 거절(1회차 한정, 복구 가능)
    CANCELLED;     // CANCELLED — 취소/만료

    public static RoundScheduleStatus from(EnrollmentRound r) {
        if (r.hasRescheduleOffer()) {
            return RESCHEDULING;
        }
        switch (r.getStatus()) {
            case PENDING:         return WAITING;
            case PAYMENT_PENDING: return PAYMENT_DUE;
            case CONFIRMED:       return CONFIRMED;
            case REJECTED:        return REJECTED;
            case CANCELLED:
            default:              return CANCELLED;
        }
    }
}
