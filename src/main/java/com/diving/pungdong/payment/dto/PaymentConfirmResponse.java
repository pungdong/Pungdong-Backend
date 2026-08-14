package com.diving.pungdong.payment.dto;

import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 결제 승인/주문 조회 응답 — 결제 자체의 결과 + <b>조회 시점의</b> 회차 상태. FE 완료 화면이 쓴다.
 *
 * <p><b>두 축이 섞여 있다는 걸 이름에 드러낸다</b>:
 * <ul>
 *   <li><b>결제의 결과</b> — {@code status}(DONE 등) + {@code amount} + {@code scheduleChange}.
 *       이건 <b>멱등</b>이다. 같은 주문을 다시 confirm 해도 같은 값이 나온다.</li>
 *   <li><b>지금의 회차 상태</b> — {@link #currentEnrollmentStatus}. 이건 <b>멱등이 아니다</b>(아래).</li>
 * </ul>
 */
@Getter
@Builder
public class PaymentConfirmResponse {

    private String orderId;     // PG 멱등키(내부용). FE 표시는 orderNo 사용
    private String orderNo;     // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화·가역)
    private PaymentStatus status;
    private int amount;
    private OffsetDateTime approvedAt;

    /**
     * 이 주문에서 <b>지금까지 환불된 누적액</b>(원). {@code status} 로는 환불 여부만 알 뿐 얼마가 돌아갔는지는
     * 안 보였다(감사 M5) — 부분환불(차액 조정)이면 {@code DONE} 인 채 이 값만 오른다. FE 가 "N원 환불됨"·잔액을
     * 표시할 근거. {@code refundableAmount = amount − refundedAmount}(취소가능 잔액)도 같이 내려 계산을 FE 에 안 미룬다.
     */
    private int refundedAmount;
    private int refundableAmount;

    /**
     * <b>회차(EnrollmentRound) id</b>. 옛 이름은 {@code enrollmentId} 였는데 담는 값은 회차 id 라
     * 이름이 거짓말을 하고 있었다 — 환불 경로({@code POST /enrollments/{enrollmentId}/refund})의
     * {@code enrollmentId} 는 <b>수강(Enrollment) id</b> 라 둘이 헷갈리는데 타입이 둘 다 number 라
     * 컴파일러도 못 잡는다. 2026-08-11 개명(FE 협의).
     */
    private Long roundId;

    /**
     * <b>이 응답을 만든 시점의</b> 회차 상태 — 결제의 결과가 <i>아니다</i>.
     *
     * <p>결제의 결과는 언제나 {@code ACCEPT_PENDING}(선결제 → 강사 결정 대기)이다. 그런데 이 필드는
     * 회차 행을 <b>live 로</b> 읽으므로, 결제와 조회 사이에 강사가 수락하면 {@code CONFIRMED} 가,
     * 거절/취소/만료면 {@code REJECTED}/{@code CANCELLED} 가 온다. 특히
     * {@code GET /payments/orders/{orderId}}(이니시스 성공화면 경로)와 <b>멱등 재-confirm</b> 에서
     * 그렇다 — 결제는 멱등인데 이 필드는 아니다.
     *
     * <p>그게 <b>의도된 동작</b>이다: 화면이 필요한 건 "결제 시점의 상태" 가 아니라 "지금 뭐라고
     * 말해줄까" 이므로 live 읽기가 맞다. 다만 옛 이름({@code enrollmentStatus})이 그 사실을 숨겨
     * "이 결제의 결과" 로 읽히는 바람에 FE 가 {@code CONFIRMED} 분기를 지우는 회귀가 났다.
     * <b>이름에 "현재" 를 박아 계약만 읽고도 예측되게 한다</b>(2026-08-11 FE 역제안 수용).
     *
     * <p>스냅샷 필드를 따로 두지 않는 이유: 결제 시점 상태를 쓰는 화면이 없고, 나란히 두면
     * "둘 중 뭘 써야 하나" 가 새 함정이 된다.
     */
    private EnrollmentStatus currentEnrollmentStatus;

    /**
     * 이 주문이 <b>일정 변경 차액</b> 결제인가 — 완료 화면 문구가 갈린다("결제가 완료됐어요" ↔ "일정 변경을
     * 요청했어요"). {@link #currentEnrollmentStatus} 는 두 경우 모두 {@code ACCEPT_PENDING} 이라 구분이 안 되고,
     * 이니시스는 성공 URL 을 BE 가 만들어 302 하므로 FE 가 쿼리로 실어보낼 수도 없다 — 그래서 서버가 알려준다.
     * 판정은 {@link PaymentOrder#isSlotChange()}(target 슬롯 4필드 유무)로, 새 컬럼 없음.
     *
     * <p>이 값은 주문에 박제된 사실이라 <b>멱등</b>이다 — 완료 화면 문구는 이걸로 가르는 게 맞다.
     */
    private boolean scheduleChange;

    public static PaymentConfirmResponse of(PaymentOrder order, String orderNo) {
        return PaymentConfirmResponse.builder()
                .orderId(order.getOrderId())
                .orderNo(orderNo)
                .status(order.getStatus())
                .amount(order.getAmount())
                .approvedAt(order.getApprovedAt())
                .refundedAmount(order.getRefundedAmount())
                .refundableAmount(order.refundableAmount())
                .roundId(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getId())
                .currentEnrollmentStatus(order.getEnrollmentRound() == null ? null : order.getEnrollmentRound().getStatus())
                .scheduleChange(order.isSlotChange())
                .build();
    }
}
