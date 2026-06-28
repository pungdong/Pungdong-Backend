package com.diving.pungdong.enrollment;

import java.util.List;

/**
 * 강사 수강관리 hub 카드의 1차 액션 플래그 — 그 수강에서 강사가 지금 해야 할 일. 액션 필요 회차의
 * {@link InstructorRoundStatus} 에서 파생. 없으면 null(액션 없음). 디자인 핸드오프의 {@code flag}.
 *
 * <ul>
 *   <li>{@code NEW_REQUEST} — 신규 신청 응답(WAITING). 가장 급함(학생 대기).</li>
 *   <li>{@code CHANGE_REQUEST} — 학생 일정변경 검토(CHANGING).</li>
 *   <li>{@code CLOSING} — 세션 종료, 마무리(done) 필요(CLOSING).</li>
 * </ul>
 */
public enum InstructorActionFlag {
    NEW_REQUEST, CHANGE_REQUEST, CLOSING;

    public static InstructorActionFlag derive(List<InstructorRoundStatus> roundStatuses) {
        if (roundStatuses.contains(InstructorRoundStatus.WAITING)) {
            return NEW_REQUEST;
        }
        if (roundStatuses.contains(InstructorRoundStatus.CHANGING)) {
            return CHANGE_REQUEST;
        }
        if (roundStatuses.contains(InstructorRoundStatus.CLOSING)) {
            return CLOSING;
        }
        return null;
    }
}
