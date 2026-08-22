package com.diving.pungdong.availability;

/**
 * 가용시간 생성 반복 모드 — "가용시간 추가" 폼의 mode("이 날만 / 이 주 / 이 달").
 *
 * <ul>
 *   <li>{@link #ONCE} — anchor 날짜 하루만. 유일하게 anchor 를 "그 날" 로 쓴다.</li>
 *   <li>{@link #WEEKLY} — anchor 가 속한 주(ISO, 월요일 시작)에서 선택 요일들(dayOfWeeks)로 전개.</li>
 *   <li>{@link #FOUR_WEEKS} — 같은 규칙을 4주에 걸쳐 전개. <b>레거시</b> — FE 는 {@link #MONTH} 로 대체했고
 *       더 이상 보내지 않는다(구 클라이언트 하위호환으로만 유지).</li>
 *   <li>{@link #MONTH} — anchor 가 속한 <b>달력 월</b>(1일~말일)에서 선택 요일들로 전개.</li>
 * </ul>
 *
 * <p><b>반복 모드에서 anchor(=요청의 date)는 "언제부터" 가 아니라 "어느 기간" 이다.</b> 전개 시작점은
 * {@code max(오늘, 기간 시작)} 이고 — 기간 시작은 MONTH 면 그 달 1일, WEEKLY·FOUR_WEEKS 면 그 주 월요일 —
 * anchor 자신이 아니다. anchor 를 시작점으로 쓰면 강사가 달·주의 중간 날을 찍었을 때 <b>기간 앞부분이
 * 조용히 빠진다</b>(9/15 를 찍고 "이 달 반복·화" → 9/1·9/8 누락). FOUR_WEEKS 가 달 끝을 빠뜨리던 것과
 * 같은 부류이고, 실패가 에러가 아니라 "덜 열림" 이라 더 나쁘다. {@code AvailabilityService.recurrenceStart}
 * 참고. ONCE 는 기간이 아니므로 해당 없음.
 */
public enum RecurrenceMode {
    ONCE,
    WEEKLY,
    FOUR_WEEKS,
    MONTH
}
