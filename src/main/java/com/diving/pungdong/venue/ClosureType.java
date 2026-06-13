package com.diving.pungdong.venue;

/**
 * 정기 휴무 규칙 종류. 한 위치가 {@code WEEKLY} 와 {@code MONTHLY} 를 동시에 가질 수 있다
 * (예: 매주 월요일 + 매월 셋째 수요일 점검 휴무 — 공공 체육시설에 흔함).
 *
 * <ul>
 *   <li>{@code WEEKLY} — 매주 X·Y요일 ({@code weekdays}).</li>
 *   <li>{@code MONTHLY} — 매월 N째 주 X요일 ({@code nths} + {@code monthlyWeekday}).</li>
 * </ul>
 */
public enum ClosureType {
    WEEKLY, MONTHLY
}
