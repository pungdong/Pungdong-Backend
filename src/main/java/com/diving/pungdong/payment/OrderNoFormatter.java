package com.diving.pungdong.payment;

import org.hashids.Hashids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 주문번호 포맷터 — CS·고객용 표시 번호. {@code PD-YYMMDD-코드}. 날짜(주문일) + 순차 {@link PaymentOrder} id 를
 * Hashids 로 난독화한 <b>비순차 코드</b>. 날짜는 사람 맥락용일 뿐 누적 주문 수는 코드가 가린다(같은 날 주문도 코드는 비순차).
 * <b>가역</b>(코드→id)이라 CS 가 주문번호로 바로 조회. 토스 {@code orderId}(멱등키, 내부)와 별개의 표시값.
 *
 * <p>전화 상담 대비 혼동 문자(0·O·1·I·L) 제외한 대문자+숫자 알파벳. {@code salt} 가 곧 키 — 노출/변경 금지
 * (바꾸면 기존 코드 decode 불가). 예: {@code PD-260628-K7MQ2X9P}.
 */
@Component
public class OrderNoFormatter {

    private static final String PREFIX = "PD-";
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // 0 O 1 I L 제외
    private static final int MIN_LENGTH = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final Hashids hashids;

    public OrderNoFormatter(@Value("${pungdong.hashids.salt}") String salt) {
        this.hashids = new Hashids(salt, MIN_LENGTH, ALPHABET);
    }

    /** id + 주문일 → {@code PD-YYMMDD-코드}. id 가 null(저장 전)이면 null. 날짜 없으면 날짜 생략. */
    public String format(Long id, LocalDateTime createdAt) {
        if (id == null) {
            return null;
        }
        String code = hashids.encode(id);
        return createdAt == null ? PREFIX + code : PREFIX + createdAt.format(DATE) + "-" + code;
    }

    /** {@code PD-YYMMDD-코드}(또는 {@code PD-코드}) → id. 형식/디코드 실패면 null. CS 가 주문번호로 조회할 때. */
    public Long parse(String orderNo) {
        if (orderNo == null || !orderNo.startsWith(PREFIX)) {
            return null;
        }
        String rest = orderNo.substring(PREFIX.length()); // "YYMMDD-코드" 또는 "코드"
        int dash = rest.lastIndexOf('-');
        String code = dash >= 0 ? rest.substring(dash + 1) : rest;
        long[] decoded = hashids.decode(code);
        return decoded.length == 1 ? decoded[0] : null;
    }
}
