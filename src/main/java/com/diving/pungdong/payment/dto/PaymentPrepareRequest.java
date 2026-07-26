package com.diving.pungdong.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 결제 준비 요청 — 수락된(PAYMENT_PENDING) <b>회차</b>에 대해 주문 생성.
 *
 * <p>⚠️ <b>식별자 이름 주의</b>: 결제 단위는 <b>회차(EnrollmentRound)</b> 다. 옛 필드명 {@code enrollmentId} 가
 * 실제로는 회차 id 를 담고 있어, 같은 이름을 <b>수강(Enrollment) id</b> 로 쓰는 환불 경로
 * ({@code POST /enrollments/{enrollmentId}/refund})와 헷갈린다 — 둘 다 number 라 타입으로 못 잡는다(FE 리뷰 지적).
 * 그래서 {@link #roundId} 를 도입했고, {@code enrollmentId} 는 하위호환으로 당분간 병행 허용한다.
 */
@Getter @Setter
@NoArgsConstructor
public class PaymentPrepareRequest {

    /** ★ 회차(EnrollmentRound) id — 권장 필드. */
    private Long roundId;

    /**
     * @deprecated 회차 id 를 담는 옛 이름. {@link #roundId} 를 쓸 것. 하위호환으로만 남아 있다.
     */
    @Deprecated
    private Long enrollmentId;

    /** 실제로 쓸 회차 id — {@code roundId} 우선, 없으면 옛 {@code enrollmentId}. 둘 다 없으면 null(컨트롤러가 400). */
    public Long resolvedRoundId() {
        return roundId != null ? roundId : enrollmentId;
    }

    /**
     * 모바일 환경 여부 — KCP 표준결제가 <b>모바일(거래등록 후 PayUrl 이동)과 PC(JS SDK 직접 호출)</b>로 흐름이
     * 갈리기 때문에 필요하다. 토스 위젯은 무관(무시). 미전달 시 PC 로 본다.
     *
     * <p>보안값이 아니라 흐름 선택값이라 클라이언트가 보내도 안전하다 — 리턴 URL 처럼 위조 시 위험한 값은
     * 받지 않고 BE 설정으로 고정한다.
     */
    private boolean mobile;

    /**
     * 클라이언트 종류 — {@code "web"} | {@code "app"} (미전달 시 web). <b>KCP 콜백 리다이렉트 타겟</b> 선택용
     * ({@link com.diving.pungdong.payment.PaymentClient}). {@code mobile} 과 독립 축 — 웹 모바일브라우저는
     * {@code mobile:true, client:"web"} 다. TOSS/STUB 는 무시. 값 자체는 리다이렉트 URL 을 정하는 게 아니라
     * BE 의 고정 allowlist 중 하나를 고르게 할 뿐이라 클라이언트가 보내도 안전(오픈 리다이렉트 불가).
     */
    private String client;
}
