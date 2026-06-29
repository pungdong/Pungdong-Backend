package com.diving.pungdong.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마케팅 야간 전송 제한(08~21 KST) 클램프 로직. 법적 핵심이라 단위로 못박는다.
 * 시스템 존에 무관하게 검증하려고 결과를 instant 로 환산해 비교한다.
 *
 * 시나리오: M* = 마케팅 윈도우.
 */
class MarketingSendWindowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant kst(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST).toInstant();
    }

    /** clamp 결과(시스템 존 LocalDateTime)를 다시 instant 로 환산. */
    private static Instant clampedInstant(Instant input) {
        LocalDateTime out = MarketingSendWindow.clamp(input);
        return out.atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    @DisplayName("M1: 새벽(02:00 KST) → 같은 날 08:00 KST 로 미룸")
    void earlyMorning_movedToOpen() {
        assertThat(clampedInstant(kst(2026, 6, 30, 2, 0)))
                .isEqualTo(kst(2026, 6, 30, 8, 0));
    }

    @Test
    @DisplayName("M2: 야간(22:00 KST) → 다음 날 08:00 KST 로 미룸")
    void lateNight_movedToNextOpen() {
        assertThat(clampedInstant(kst(2026, 6, 30, 22, 0)))
                .isEqualTo(kst(2026, 7, 1, 8, 0));
    }

    @Test
    @DisplayName("M3: 낮(12:00 KST) → 그대로(허용 시간대)")
    void daytime_unchanged() {
        assertThat(clampedInstant(kst(2026, 6, 30, 12, 0)))
                .isEqualTo(kst(2026, 6, 30, 12, 0));
    }

    @Test
    @DisplayName("M4: 경계 — 07:59 → 08:00, 08:00 정각 → 허용(그대로)")
    void openBoundary() {
        assertThat(clampedInstant(kst(2026, 6, 30, 7, 59))).isEqualTo(kst(2026, 6, 30, 8, 0));
        assertThat(clampedInstant(kst(2026, 6, 30, 8, 0))).isEqualTo(kst(2026, 6, 30, 8, 0));
    }

    @Test
    @DisplayName("M5: 경계 — 20:59 → 그대로, 21:00 정각 → 야간(다음 날 08:00)")
    void closeBoundary() {
        assertThat(clampedInstant(kst(2026, 6, 30, 20, 59))).isEqualTo(kst(2026, 6, 30, 20, 59));
        assertThat(clampedInstant(kst(2026, 6, 30, 21, 0))).isEqualTo(kst(2026, 7, 1, 8, 0));
    }
}
