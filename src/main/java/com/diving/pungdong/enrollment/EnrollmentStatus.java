package com.diving.pungdong.enrollment;

import java.util.List;

/**
 * 수강신청 상태 — V2 디자인 booking 흐름 "신청 → 강사 답변 대기 → 수락 → 결제 → 확정".
 *
 * <ul>
 *   <li>{@link #PENDING} — 신청 완료, 강사 답변 대기(디자인 ⑥ "강사 답변 대기"). <b>신청 시점에 좌석 lock</b>
 *       (선착순 — 정원 차면 새 신청 거절). 24h 무응답 시 자동 만료(슬롯 해제). availability 캘린더 pending dot.</li>
 *   <li>{@link #PAYMENT_PENDING} — 강사 수락 직후 결제 대기(디자인 "강사 확정 후 결제 링크 푸시"). 슬롯을
 *       계속 점유(좌석 hold). 결제 승인 시 {@link #CONFIRMED} 로 넘어감. 12h 미결제 시 자동 만료(슬롯 해제).</li>
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

    /** 좌석을 점유하는 활성 상태(대기·결제대기·확정) — <b>신청 시점 lock</b> 이라 PENDING 도 만석 판정에 포함.
     *  거절/취소/만료는 슬롯을 비운다. */
    public boolean isActive() {
        return this == PENDING || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** <b>확정 점유</b>(결제대기·확정) — 캘린더 confirmed 버킷 표시용. (만석 판정은 {@link #ACTIVE} — 신청 시점 lock.) */
    public boolean occupiesCapacity() {
        return this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** 좌석 점유(만석 판정·세션 삭제 가드·캘린더 집계) 상태 집합 — 신청 시점 lock. {@link #isActive()} 컬렉션. */
    public static final List<EnrollmentStatus> ACTIVE = List.of(PENDING, PAYMENT_PENDING, CONFIRMED);

    /** 확정 점유(결제대기+확정) — 캘린더 confirmed 버킷 표시. {@link #occupiesCapacity()} 컬렉션. (만석=ACTIVE.) */
    public static final List<EnrollmentStatus> OCCUPYING = List.of(PAYMENT_PENDING, CONFIRMED);
}
