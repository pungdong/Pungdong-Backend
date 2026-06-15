package com.diving.pungdong.availability;

/**
 * 가용시간 생성 반복 모드 — V2 디자인 "가용시간 추가" 폼의 mode("이 날만 / 주 / 4주", chat8 §6).
 *
 * <ul>
 *   <li>{@link #ONCE} — anchor 날짜 하루만 1개 window.</li>
 *   <li>{@link #WEEKLY} — anchor 가 속한 주에서 선택 요일들(dayOfWeeks)로 window 전개(과거일 제외).</li>
 *   <li>{@link #FOUR_WEEKS} — 같은 규칙을 4주에 걸쳐 전개.</li>
 * </ul>
 */
public enum RecurrenceMode {
    ONCE,
    WEEKLY,
    FOUR_WEEKS
}
