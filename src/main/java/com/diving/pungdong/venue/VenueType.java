package com.diving.pungdong.venue;

/**
 * 위치 유형 — 풍덩 전반의 핵심 어휘. 입장/시간 정책이 유형마다 크게 다르다.
 *
 * <ul>
 *   <li>{@code POOL_5M} — 5m 풀 (일반 수영장 잠수 가능 레인)</li>
 *   <li>{@code DEEP_POOL} — 딥풀 (다이빙 전용 깊은 풀, 예: 딥스테이션)</li>
 *   <li>{@code OCEAN} — 해양 (바다 포인트 · 다이빙 포인트). 강사 커스텀 위치가 주로 이 유형.</li>
 * </ul>
 */
public enum VenueType {
    POOL_5M, DEEP_POOL, OCEAN
}
