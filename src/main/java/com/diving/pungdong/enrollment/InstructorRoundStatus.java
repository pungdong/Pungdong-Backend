package com.diving.pungdong.enrollment;

import java.time.LocalDate;

/**
 * 강사 시점 회차 상태 — 강사 수강관리 hub(거래 카드)의 회차 칩. {@link EnrollmentStatus}(저장값) + doneAt + 슬롯이력 +
 * 제안 + 세션일을 강사 관점으로 파생(저장 X). 학생 hub 의 {@link RoundScheduleStatus} 거울.
 *
 * <ul>
 *   <li>{@code WAITING} — 신규 신청(PENDING, 제안·변경 없음). 강사가 수락/거절/일정변경요청.</li>
 *   <li>{@code CHANGING} — 학생이 직접 일정수정(PENDING + 슬롯이력 有). 강사가 변경 검토(수락/재제안).</li>
 *   <li>{@code PROPOSED} — 강사가 일정변경요청함(PENDING + 제안 有). 학생 선택 대기(강사 액션 아님).</li>
 *   <li>{@code PAYMENT_DUE} — 수락됨, 학생 결제 대기(PAYMENT_PENDING).</li>
 *   <li>{@code CONFIRMED} — 결제·확정, 세션 미도래(진행 예정).</li>
 *   <li>{@code CLOSING} — 확정 + 세션일 지남 + 미완료. 강사가 마무리(done).</li>
 *   <li>{@code DONE} — 수강 완료(CONFIRMED + doneAt).</li>
 *   <li>{@code REJECTED}/{@code CANCELLED} — 거절/취소.</li>
 * </ul>
 */
public enum InstructorRoundStatus {
    WAITING, CHANGING, PROPOSED, PAYMENT_DUE, CONFIRMED, CLOSING, DONE, REJECTED, CANCELLED;

    public static InstructorRoundStatus from(EnrollmentRound r, LocalDate today) {
        switch (r.getStatus()) {
            case PENDING:
                if (r.hasRescheduleOffer()) {
                    return PROPOSED; // 강사가 제안함 → 학생 차례
                }
                if (!r.getSlotHistory().isEmpty()) {
                    return CHANGING; // 학생이 직접 일정수정 → 강사 검토
                }
                return WAITING; // 신규 신청
            case PAYMENT_PENDING:
                return PAYMENT_DUE;
            case CONFIRMED:
                if (r.getDoneAt() != null) {
                    return DONE;
                }
                return (r.getDate() != null && r.getDate().isBefore(today)) ? CLOSING : CONFIRMED;
            case REJECTED:
                return REJECTED;
            case CANCELLED:
            default:
                return CANCELLED;
        }
    }

    /** 강사가 행동해야 하는 상태(액션 필요). */
    public boolean needsAction() {
        return this == WAITING || this == CHANGING || this == CLOSING;
    }
}
