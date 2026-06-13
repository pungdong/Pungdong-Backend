package com.diving.pungdong.venue;

/**
 * 하루 파트 — 한 이용권의 가격·시간은 평일/주말로 나뉜다.
 *
 * <p>{@code WEEKEND} 는 주말 + 공휴일을 함께 의미한다(별도 공휴일 파트는 없음). {@code WEEKDAY} 는
 * 항상 판매(있음)이고, {@code WEEKEND} 만 판매 안 함({@code sold = false})이 될 수 있다.
 */
public enum DaypartKind {
    WEEKDAY, WEEKEND
}
