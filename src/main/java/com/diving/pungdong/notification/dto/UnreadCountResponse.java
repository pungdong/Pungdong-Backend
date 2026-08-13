package com.diving.pungdong.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 미읽음 개수 (뱃지용).
 *
 * <p>0 건도 정상 응답 {@code 200} 이다 — 레포 규칙상 비즈니스 "부정" 답은 4xx 가 아니라 200 + 필드다.
 */
@Getter
@AllArgsConstructor
public class UnreadCountResponse {
    private final long count;
}
