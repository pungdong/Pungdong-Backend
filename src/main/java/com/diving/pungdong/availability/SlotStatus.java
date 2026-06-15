package com.diving.pungdong.availability;

/**
 * 슬롯(가용시간 window) 표시 상태 — V2 디자인의 5가지 슬롯 상태. <b>저장값이 아니라 점유에서 파생</b>한다
 * ({@link AvailabilityService#deriveStatus}). 강사 캘린더가 한 window 를 어떤 톤으로 그릴지의 단일 출처.
 *
 * <ul>
 *   <li>{@link #AVAILABLE} — 빈 가용시간(점유 0). 신청 대기 가능.</li>
 *   <li>{@link #PENDING} — 풍덩 수강생 신청이 들어왔고 강사 수락 전. (enrollment 도메인 산물 — v1 미연동, 항상 0.)</li>
 *   <li>{@link #CONFIRMED} — 풍덩 확정 점유가 있고 정원 여유. (enrollment 도메인 산물 — v1 미연동.)</li>
 *   <li>{@link #EXTERNAL} — 외부/수동 hold 점유 포함(정원 여유). v1 에서 실제로 그려지는 점유 상태.</li>
 *   <li>{@link #FULL} — 정원이 다 참(filled &gt;= capacity).</li>
 * </ul>
 *
 * <p>v1 은 enrollment 가 없어 {@code PENDING}/{@code CONFIRMED} 은 항상 비고, 캘린더는
 * {@code AVAILABLE} ↔ {@code EXTERNAL}/{@code FULL} 만 그린다. 5상태 모델은 완비 — enrollment 가 붙으면 채워진다.
 */
public enum SlotStatus {
    AVAILABLE,
    PENDING,
    CONFIRMED,
    EXTERNAL,
    FULL
}
