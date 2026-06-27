package com.diving.pungdong.payment.dto;

import lombok.Getter;

import java.util.List;

/**
 * 환불 견적 — 수강 종료(남은 회차 환불) 시 회차별로 계산한 환불액 합 + 내역. 표시(미리보기)와 실행(토스 취소)이
 * 같은 값을 쓴다. 회차 단위 계산: done=0, 미배정 회차=수강료/N(100%), 배정취소=(수강료/N+부대)×환불율.
 */
@Getter
public class RefundQuote {

    private final int total;
    private final List<Line> lines;

    public RefundQuote(int total, List<Line> lines) {
        this.total = total;
        this.lines = lines;
    }

    /**
     * 회차(또는 미배정 회차분) 1줄. roundId 는 잡힌 회차만(미배정은 null). 실행 매핑을 위해 수강료 몫·부대 몫을
     * 분리: <b>수강료 몫(tuitionPart)은 1회차 결제주문</b>(전액 거기서 냄)에서, <b>부대 몫(extraPart)은 그 회차 주문</b>에서 취소.
     */
    @Getter
    public static class Line {
        private final Integer roundIndex;
        private final Long roundId;
        private final int amount;       // 이 줄 환불액 = tuitionPart + extraPart
        private final int tuitionPart;  // 수강료 몫(→ 1회차 주문 부분취소)
        private final int extraPart;    // 부대 몫(→ 그 회차 주문 부분취소)
        private final int ratePct;      // 적용 환불율(0~100)
        private final String reason;

        public Line(Integer roundIndex, Long roundId, int tuitionPart, int extraPart, int ratePct, String reason) {
            this.roundIndex = roundIndex;
            this.roundId = roundId;
            this.tuitionPart = tuitionPart;
            this.extraPart = extraPart;
            this.amount = tuitionPart + extraPart;
            this.ratePct = ratePct;
            this.reason = reason;
        }
    }
}
