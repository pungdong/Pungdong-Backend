package com.diving.pungdong.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문번호 난독화 검증 — 순수 단위. PD-YYMMDD-코드 · 가역(코드↔id) · 순차 id 가 비순차 코드 · 혼동문자 없음. */
class OrderNoFormatterTest {

    private final OrderNoFormatter f = new OrderNoFormatter("test-salt-fixed");
    private final OffsetDateTime t = OffsetDateTime.of(2026, 6, 28, 14, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("N1 PD-YYMMDD-코드 + 가역(코드→id) + 연속 id 가 안 이어 보임 + 혼동문자(0O1IL) 없음")
    void formatAndParse() {
        // 형식: PD-260628-코드 (날짜 + 코드)
        assertThat(f.format(123L, t)).startsWith("PD-260628-");

        // 가역: 날짜가 붙어도 코드만 디코드해서 원래 id
        assertThat(f.parse(f.format(123L, t))).isEqualTo(123L);
        assertThat(f.parse(f.format(1L, t))).isEqualTo(1L);

        // 연속 id 1·2·3 이 비순차(코드 부분이 서로 다르고 순번 유추 불가) — 날짜 같아도
        assertThat(f.format(1L, t)).isNotEqualTo(f.format(2L, t));

        // 혼동 문자(0 O 1 I L) 없음 — 전화 상담 안전 (코드 부분)
        String code = f.format(99999L, t).substring("PD-260628-".length());
        assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");

        // 날짜 없으면 PD-코드 (날짜 생략) + 그래도 가역
        assertThat(f.format(123L, null)).startsWith("PD-").doesNotContain("260628");
        assertThat(f.parse(f.format(123L, null))).isEqualTo(123L);

        // null / 잘못된 입력
        assertThat(f.format(null, t)).isNull();
        assertThat(f.parse("garbage")).isNull();
        assertThat(f.parse(null)).isNull();
    }
}
