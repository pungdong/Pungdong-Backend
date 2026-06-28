package com.diving.pungdong.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문번호 난독화 검증 — 순수 단위. 가역(코드↔id) · 순차 id 가 비순차 코드 · 혼동문자 없음. */
class OrderNoFormatterTest {

    private final OrderNoFormatter f = new OrderNoFormatter("test-salt-fixed");

    @Test
    @DisplayName("N1 PD- 접두 + 가역(코드→id) + 연속 id 가 안 이어 보임 + 혼동문자(0O1IL) 없음")
    void formatAndParse() {
        // 가역: format → parse 면 원래 id
        assertThat(f.parse(f.format(123L))).isEqualTo(123L);
        assertThat(f.parse(f.format(1L))).isEqualTo(1L);

        // PD- 접두
        assertThat(f.format(1L)).startsWith("PD-");

        // 연속 id 1·2·3 이 비순차(서로 다르고, 코드만 봐서 순번 유추 불가)
        assertThat(f.format(1L)).isNotEqualTo(f.format(2L));

        // 혼동 문자(0 O 1 I L) 없음 — 전화 상담 안전
        String code = f.format(99999L).substring(3);
        assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");

        // null / 잘못된 입력
        assertThat(f.format(null)).isNull();
        assertThat(f.parse("garbage")).isNull();
        assertThat(f.parse(null)).isNull();
    }
}
