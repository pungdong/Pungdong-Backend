package com.diving.pungdong.enrollment;

import java.util.List;

/**
 * 수강신청 상태 — V2 디자인 booking 흐름 "신청 → 강사 답변 대기 → 수락 → 결제 → 확정".
 *
 * <ul>
 *   <li>{@link #PENDING} — 신청 완료, 강사 답변 대기(디자인 ⑥ "강사 답변 대기"). 정원 하드캡 안 함(여러 건
 *       쌓여도 강사가 수락/거절로 정리). availability 캘린더에서 pending dot.</li>
 *   <li>{@link #PAYMENT_PENDING} — 강사 수락 직후 결제 대기(디자인 "강사 확정 후 결제 링크 푸시"). 슬롯을
 *       점유(좌석 hold) — 결제가 끝날 때까지 남이 못 채간다. 결제 승인 시 {@link #CONFIRMED} 로 넘어감.
 *       (결제 미완 자동 만료·환불 상태기계는 후속.)</li>
 *   <li>{@link #CONFIRMED} — 결제 완료 = 확정. 정원(점유)을 차지. availability 캘린더 confirmed.</li>
 *   <li>{@link #REJECTED} — 강사 거절(복구 가능 — 학생이 다른 일정 재신청).</li>
 *   <li>{@link #CANCELLED} — 학생이 대기 중 취소.</li>
 * </ul>
 */
public enum EnrollmentStatus {
    PENDING,
    PAYMENT_PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    /** 정원·바인딩을 점유하는 활성 상태(대기·결제대기·확정). 거절/취소는 슬롯을 비운다. */
    public boolean isActive() {
        return this == PENDING || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** 정원을 점유(만석 판정)하는 상태 — 결제대기·확정. PENDING 은 하드캡 안 함(강사가 수락으로 정리). */
    public boolean occupiesCapacity() {
        return this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** 활성(세션 삭제 가드·캘린더 집계용) 상태 집합 — {@link #isActive()} 의 컬렉션 형태. */
    public static final List<EnrollmentStatus> ACTIVE = List.of(PENDING, PAYMENT_PENDING, CONFIRMED);

    /** 정원 점유(만석 판정) 상태 집합 — {@link #occupiesCapacity()} 의 컬렉션 형태. */
    public static final List<EnrollmentStatus> OCCUPYING = List.of(PAYMENT_PENDING, CONFIRMED);
}
