package com.diving.pungdong.payment;

import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문번호 포맷터 — CS·고객용 표시 번호. 순차 {@link PaymentOrder} id 를 Hashids 로 <b>비순차 코드</b>로
 * 난독화(누적 주문 수 유추 방지) + {@code PD-} 접두. <b>가역</b>(코드→id)이라 CS 가 코드로 바로 조회 가능.
 * 토스 {@code orderId}(멱등키, 내부용)와 별개의 표시값.
 *
 * <p>전화 상담 대비 혼동 문자(0·O·1·I·L) 제외한 대문자+숫자 알파벳. {@code salt} 가 곧 키 — 노출/변경 금지
 * (바꾸면 기존 코드 decode 불가). 예: {@code PD-K7MQ2X9P}.
 */
@Component
public class OrderNoFormatter {

    private static final String PREFIX = "PD-";
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // 0 O 1 I L 제외
    private static final int MIN_LENGTH = 8;

    private final Hashids hashids;

    public OrderNoFormatter(@Value("${pungdong.hashids.salt}") String salt) {
        this.hashids = new Hashids(salt, MIN_LENGTH, ALPHABET);
    }

    /** id → {@code PD-XXXXXXXX}. id 가 null(저장 전)이면 null. */
    public String format(Long id) {
        return id == null ? null : PREFIX + hashids.encode(id);
    }

    /** {@code PD-XXXXXXXX} → id. 형식/디코드 실패면 null. CS 가 주문번호로 조회할 때. */
    public Long parse(String orderNo) {
        if (orderNo == null || !orderNo.startsWith(PREFIX)) {
            return null;
        }
        long[] decoded = hashids.decode(orderNo.substring(PREFIX.length()));
        return decoded.length == 1 ? decoded[0] : null;
    }
}
