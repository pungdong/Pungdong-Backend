package com.diving.pungdong.enrollment;

import java.util.List;

/**
 * 강사 수강관리 hub 의 거래 카드 상태 — 한 수강(수강생×강의) 묶음의 강사 관점 상태. 회차들의
 * {@link InstructorRoundStatus} 에서 파생(저장 X). 정렬·필터의 단위. (학생 hub {@link CourseScheduleStatus} 거울.)
 *
 * <ul>
 *   <li>{@code ACTION_NEEDED} — 강사가 응답해야 할 회차 있음(WAITING/CHANGING/CLOSING). 최우선 정렬.</li>
 *   <li>{@code PROGRESS} — 진행중(확정·결제대기·제안중 등 활성, 즉시 액션은 없음).</li>
 *   <li>{@code COMPLETED} — 정규 회차 전부 완료(done).</li>
 *   <li>{@code CANCELLED} — 활성 회차 없음(전부 취소/거절).</li>
 * </ul>
 */
public enum InstructorEnrollmentStatus {
    ACTION_NEEDED, PROGRESS, COMPLETED, CANCELLED;

    /**
     * @param roundStatuses 활성/이력 회차들의 강사 시점 상태
     * @param totalRegular  정규 회차 총 수
     * @param doneRegular   완료(done)된 정규 회차 수
     */
    public static InstructorEnrollmentStatus derive(List<InstructorRoundStatus> roundStatuses,
                                                    int totalRegular, int doneRegular) {
        if (roundStatuses.stream().anyMatch(InstructorRoundStatus::needsAction)) {
            return ACTION_NEEDED;
        }
        boolean anyActive = roundStatuses.stream().anyMatch(s ->
                s == InstructorRoundStatus.PROPOSED || s == InstructorRoundStatus.PAYMENT_DUE
                        || s == InstructorRoundStatus.CONFIRMED);
        if (anyActive) {
            return PROGRESS;
        }
        if (totalRegular > 0 && doneRegular >= totalRegular) {
            return COMPLETED;
        }
        // 활성 없음 + 미완료 — 완료 회차가 하나라도 있으면 진행중(나머지 미배정), 아니면 취소.
        boolean anyDone = roundStatuses.stream().anyMatch(s -> s == InstructorRoundStatus.DONE);
        return anyDone ? PROGRESS : CANCELLED;
    }
}
