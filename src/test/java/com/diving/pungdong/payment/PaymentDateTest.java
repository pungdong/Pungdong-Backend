package com.diving.pungdong.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 날짜의 KST 경계 — 외부 호출 없이(hermetic) 고정한다. 저장은 UTC instant 지만 <b>사람·정책이 보는 날짜는 KST</b> 다.
 *
 * <p><b>왜 돈이 걸리나</b>: 환불율은 세션일까지 남은 '일수'로 갈리는데(당일 0% / 전날 50% / …), 오늘 날짜를 UTC 로 잡으면
 * KST 00~09시 취소가 하루 밀려 한 칸 유리해진다. 주문번호(PD-YYMMDD-…)도 같은 이유로 전날 날짜로 찍혔었다.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. D* 날짜 경계.
 */
class PaymentDateTest {

    /* ─── D* 환불율 기준 '오늘' (RefundService.businessToday) ─── */

    @Test
    @DisplayName("D1 KST 자정~오전9시(= UTC 전날 밤) 취소는 UTC 가 아니라 KST 날짜로 잡힌다")
    void businessTodayUsesKstDate() {
        // UTC 2026-08-10 23:30 = KST 2026-08-11 08:30 → 오늘은 08-11 (UTC 로 잡으면 08-10 이라 하루 유리)
        assertThat(RefundService.businessToday(OffsetDateTime.parse("2026-08-10T23:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    @DisplayName("D2 낮 시간대는 UTC·KST 날짜가 같아 경계 문제 없음")
    void businessTodaySameWhenDaytime() {
        // UTC 2026-08-11 05:00 = KST 14:00 → 같은 날 08-11
        assertThat(RefundService.businessToday(OffsetDateTime.parse("2026-08-11T05:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    @DisplayName("D3 KST 자정 직후(= UTC 당일 오후 3시 이후)는 다음 날로 넘어간다")
    void businessTodayCrossesMidnightForward() {
        // UTC 2026-08-11 15:30 = KST 2026-08-12 00:30 → 오늘은 08-12
        assertThat(RefundService.businessToday(OffsetDateTime.parse("2026-08-11T15:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 12));
    }

    /* ─── D* 주문번호 표시 날짜 (OrderNoFormatter) ─── */

    @Test
    @DisplayName("D4 주문번호(PD-YYMMDD-코드)의 날짜는 KST 기준 — UTC 전날로 찍히지 않는다")
    void orderNoDateUsesKst() {
        OrderNoFormatter formatter = new OrderNoFormatter("test-salt-pungdong");

        // UTC 2026-08-10 23:30 = KST 2026-08-11 → 260811, 260810 아님
        String orderNo = formatter.format(42L, OffsetDateTime.parse("2026-08-10T23:30:00Z"));

        assertThat(orderNo).startsWith("PD-260811-")
                .doesNotContain("260810");
    }
}
