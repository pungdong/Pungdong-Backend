package com.diving.pungdong.course;

/**
 * 회차 종류. {@code EXTRA}(추가세션)는 정규 과정에 안 들어가는 보충 세션이라 비용 정책
 * ({@code freeCount} 회까지 무료, 이후 회당 {@code perSessionPrice})을 추가로 가진다(chat19).
 */
public enum RoundKind {
    REGULAR, EXTRA
}
