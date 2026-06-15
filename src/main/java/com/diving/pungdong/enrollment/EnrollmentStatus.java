package com.diving.pungdong.enrollment;

/**
 * 수강신청 상태 — V2 디자인 booking 흐름 "신청 → 강사 답변 대기 → 수락/거절".
 *
 * <ul>
 *   <li>{@link #PENDING} — 신청 완료, 강사 답변 대기(디자인 ⑥ "강사 답변 대기"). 정원 하드캡 안 함(여러 건
 *       쌓여도 강사가 수락/거절로 정리). availability 캘린더에서 pending dot.</li>
 *   <li>{@link #CONFIRMED} — 강사 수락. v1 은 결제가 없어 수락=확정(디자인 풀버전은 수락→결제→확정).
 *       정원(confirmed+외부hold)을 차지. availability 캘린더 confirmed.</li>
 *   <li>{@link #REJECTED} — 강사 거절(복구 가능 — 학생이 다른 일정 재신청).</li>
 *   <li>{@link #CANCELLED} — 학생이 대기 중 취소.</li>
 * </ul>
 */
public enum EnrollmentStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    /** 정원·바인딩을 점유하는 활성 상태(대기 또는 확정). 거절/취소는 슬롯을 비운다. */
    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
