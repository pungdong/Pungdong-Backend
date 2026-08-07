package com.diving.pungdong.enrollment;

import java.util.List;

/**
 * 수강신청 상태 — V2 디자인 booking 흐름 "신청 → 강사 답변 대기 → 수락 → 결제 → 확정".
 *
 * <p><b>선결제(pay-first) 전환</b>: 1회차 신청 흐름은 "신청 → 즉시 결제 → 강사 수락/거절"이다.
 *
 * <ul>
 *   <li>{@link #PENDING} — 신청 완료, <b>결제 대기</b>(선결제: 신청 직후 결제창으로). <b>신청 시점에 좌석 lock</b>
 *       (선착순 — 정원 차면 새 신청 거절). 12h 미결제 시 자동 만료(슬롯 해제, 환불 없음). availability 캘린더 pending dot.</li>
 *   <li>{@link #ACCEPT_PENDING} — <b>결제완료, 강사 수락 대기</b>(선결제 핵심 상태). 슬롯 점유. 강사 수락 시
 *       {@link #CONFIRMED}, 거절/24h 무응답 시 {@link #CANCELLED}/{@link #REJECTED} + <b>자동환불</b>.</li>
 *   <li>{@link #PAYMENT_PENDING} — (2회차+ 잔존 경로) 강사 사전수락 후 결제 대기. 12h 미결제 자동 만료.</li>
 *   <li>{@link #CONFIRMED} — 결제완료 + 강사 수락 = 확정. availability 캘린더 confirmed.</li>
 *   <li>{@link #REJECTED} — 강사 거절(복구 가능 — 학생 재신청). 선결제분은 거절 시 자동환불.</li>
 *   <li>{@link #CANCELLED} — 학생 취소 또는 만료. 결제완료분은 취소/만료 시 자동환불.</li>
 * </ul>
 */
public enum EnrollmentStatus {
    PENDING,
    ACCEPT_PENDING,
    PAYMENT_PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    /** 좌석을 점유하는 활성 상태(대기·결제완료대기·결제대기·확정) — <b>신청 시점 lock</b> 이라 PENDING 도 만석 판정에 포함.
     *  거절/취소/만료는 슬롯을 비운다. */
    public boolean isActive() {
        return this == PENDING || this == ACCEPT_PENDING || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** <b>확정 점유</b>(결제완료대기·결제대기·확정) — 캘린더 confirmed 버킷 표시용. (만석 판정은 {@link #ACTIVE} — 신청 시점 lock.) */
    public boolean occupiesCapacity() {
        return this == ACCEPT_PENDING || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** 좌석 점유(만석 판정·세션 삭제 가드·캘린더 집계) 상태 집합 — 신청 시점 lock. {@link #isActive()} 컬렉션. */
    public static final List<EnrollmentStatus> ACTIVE = List.of(PENDING, ACCEPT_PENDING, PAYMENT_PENDING, CONFIRMED);

    /** 확정 점유(결제완료대기+결제대기+확정) — 캘린더 confirmed 버킷 표시. {@link #occupiesCapacity()} 컬렉션. (만석=ACTIVE.) */
    public static final List<EnrollmentStatus> OCCUPYING = List.of(ACCEPT_PENDING, PAYMENT_PENDING, CONFIRMED);
}
