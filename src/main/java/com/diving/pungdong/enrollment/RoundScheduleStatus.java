package com.diving.pungdong.enrollment;

/**
 * 회차(=EnrollmentRound 1건) 진행상태 — 수강생 강의일정 hub 표시용 파생값(저장 X).
 * BE {@link EnrollmentStatus} + 일정변경 제안(proposedDates)을 설계 회차 어휘로 매핑한다.
 *
 * <p>아직 잡지 않은 미래 회차(locked)는 hub 가 {@code rounds[]} 에 안 넣고 {@code totalRounds}/{@code nextRoundIndex}
 * 로 내려, FE 가 placeholder 를 그린다. 설계의 {@code done/payment_expired} 는 출석/만료 추적 후속이라 미매핑.
 */
public enum RoundScheduleStatus {
    WAITING,       // ACCEPT_PENDING(결제완료·강사 결정 대기, 제안 없음) — 강사 확인 중
    RESCHEDULING,  // ACCEPT_PENDING + 강사 일정변경 제안 — 학생이 제안 슬롯 선택(ㅇㅋ/ㄴㄴ) 대기
    PAYMENT_DUE,   // PENDING — 미결제(신청 직후). 전 회차 동일
    CONFIRMED,     // CONFIRMED, 미완료 — 확정(결제 완료), 진행 대기
    DONE,          // CONFIRMED + doneAt — 회차 수강 완료
    REJECTED,      // REJECTED — 강사 거절(전 회차 · 그 회차만 · 자동환불 · 재신청으로 복구 가능)
    CANCELLED;     // CANCELLED — 취소/만료

    public static RoundScheduleStatus from(EnrollmentRound r) {
        if (r.hasRescheduleOffer()) {
            return RESCHEDULING;
        }
        if (r.isDone()) {
            return DONE;
        }
        switch (r.getStatus()) {
            // 선결제(전 회차): 미결제(PENDING) = 학생이 결제해야 함 → PAYMENT_DUE.
            case PENDING:         return PAYMENT_DUE;
            case ACCEPT_PENDING:  return WAITING;   // 결제완료·강사 결정 중 — 학생은 대기
            case CONFIRMED:       return CONFIRMED;
            case REJECTED:        return REJECTED;
            case CANCELLED:
            default:              return CANCELLED;
        }
    }
}
