package com.diving.pungdong.venue;

/**
 * 위치 유형 — 풍덩 전반의 핵심 어휘. 입장/시간 정책이 유형마다 크게 다르다.
 * 유형은 거친 분류, 정확한 깊이는 {@code Venue.maxDepth}(최대수심 m)로 따로 받는다.
 *
 * <ul>
 *   <li>{@code SHALLOW_POOL} — 얕은 풀장 (대략 10m 이하, 일반 수영장 잠수 가능 레인)</li>
 *   <li>{@code DEEP_POOL} — 딥풀 (다이빙 전용 깊은 풀, 예: 딥스테이션)</li>
 *   <li>{@code OCEAN} — 해양 (바다 포인트 · 다이빙 포인트). 강사 커스텀 위치가 주로 이 유형.</li>
 * </ul>
 */
public enum VenueType {
    SHALLOW_POOL, DEEP_POOL, OCEAN
}
