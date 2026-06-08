package com.diving.pungdong.instructorapplication;

/**
 * 강사 신청 한 건의 라이프사이클 상태.
 *
 * <pre>
 *   SUBMITTED  제출됨 (어드민 검토 대기 — UI 상 "자격 검토 중")
 *      │ approve              │ reject
 *      ▼                      ▼
 *   APPROVED               REJECTED ──(신청자 수정·재제출)──▶ SUBMITTED
 * </pre>
 *
 * 별도의 UNDER_REVIEW 단계는 두지 않는다 — 검토 중 표시는 SUBMITTED 로 충분하고,
 * 상태가 늘수록 상태 전이 분기가 복잡해진다. 필요해지면 그때 추가.
 */
public enum InstructorApplicationStatus {
    SUBMITTED, APPROVED, REJECTED
}
