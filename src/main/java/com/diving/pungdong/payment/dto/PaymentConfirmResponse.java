package com.diving.pungdong.payment.dto;

import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/** 결제 승인 응답 — 결제 상태 + 그 결과로 확정된 회차 상태. {@code enrollmentId} 는 회차 id. FE 가 완료 화면에 쓴다. */
@Getter
@Builder
public class PaymentConfirmResponse {

    private String orderId;     // 토스 멱등키(내부용). FE 표시는 orderNo 사용
    private String orderNo;     // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화·가역)
    private PaymentStatus status;
    private int amount;
    private OffsetDateTime approvedAt;
    private Long enrollmentId;
    private EnrollmentStatus enrollmentStatus;

    /**
     * 이 주문이 <b>일정 변경 차액</b> 결제인가 — 완료 화면 문구가 갈린다("결제가 완료됐어요" ↔ "일정 변경을
     * 요청했어요"). {@code enrollmentStatus} 는 두 경우 모두 {@code ACCEPT_PENDING} 이라 구분이 안 되고,
     * 이니시스는 성공 URL 을 BE 가 만들어 302 하므로 FE 가 쿼리로 실어보낼 수도 없다 — 그래서 서버가 알려준다.
     * 판정은 {@link PaymentOrder#isSlotChange()}(target 슬롯 4필드 유무)로, 새 컬럼 없음.
     */
    private boolean scheduleChange;

    public static PaymentConfirmResponse of(PaymentOrder order, String orderNo) {
        return PaymentConfirmResponse.builder()
                .orderId(order.getOrderId())
                .orderNo(orderNo)
                .status(order.getStatus())
                .amount(order.getAmount())
                .approvedAt(order.getApprovedAt())
                .enrollmentId(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getId())
                .enrollmentStatus(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getStatus())
                .scheduleChange(order.isSlotChange())
                .build();
    }
}
