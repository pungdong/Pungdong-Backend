package com.diving.pungdong.venue;

/**
 * 시간 제공 방식 (이용 옵션 daypart 의 시간 축).
 *
 * <ul>
 *   <li>{@code FIXED} — 고정 시간대. 정해진 "부" 리스트({@link VenueTimeBlock}) 중 수강생이 하나 선택.</li>
 *   <li>{@code OPEN} — 상시 입장. 오픈~클로즈 창({@code openStart}~{@code openEnd}) 안에서 수강생이
 *       시작 시각을 직접 정하고, 입장 후 키반납까지 {@code holdHours} 시간 이용.</li>
 *   <li>{@code SAME} — <b>주말 전용</b>. "평일과 동일" — 평일 시간 구성을 그대로 따른다(가격만 다를 수
 *       있음). 평일 daypart 엔 쓰이지 않는다.</li>
 * </ul>
 */
public enum TimeMode {
    FIXED, OPEN, SAME
}
