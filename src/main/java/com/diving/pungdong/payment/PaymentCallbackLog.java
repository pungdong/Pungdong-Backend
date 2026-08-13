package com.diving.pungdong.payment;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 이니시스 콜백 <b>수신 기록</b> — 성공·인증실패·위조(미상 P_OID)·승인실패까지 <b>모든</b> 콜백을 1건씩 남긴다.
 *
 * <p><b>왜 필요한가</b>(M-2/M-3): 콜백은 {@code permitAll} 인데 지금까지 수신 사실이 로그로만 남고 DB 엔 0 이었다.
 * 그래서 (1) "이니시스는 보냈다는데 우리는 못 받았다" 분쟁을 못 풀고, (2) 위조/인증실패 콜백으로 공격 시도를
 * 탐지 못 하며, (3) 승인이 실패한 콜백의 {@code P_AUTH_TID}/{@code P_TID}(= 이니시스에 다시 물어볼 유일한 키)를
 * 붙잡아 두지 못했다. 이 표가 그 셋을 메운다 — 승인 성패와 무관하게 별도 트랜잭션으로 무조건 남긴다.
 */
@Entity
@Table(name = "payment_callback_log")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PaymentCallbackLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 콜백이 실어 보낸 주문번호({@code P_OID}) — 미상(위조)이면 그대로 남겨 조사에 쓴다. */
    private String orderId;

    /** {@code P_STATUS} — "00" 이면 인증성공. */
    @Column(length = 8)
    private String pStatus;

    /** {@code P_AUTH_TID} — 승인/대사에 쓰는 인증 거래 식별자. <b>이니시스에 되물을 유일한 키</b>라 반드시 보존. */
    private String authTid;

    /** {@code P_TID} — 승인 거래번호(있을 때). */
    private String tid;

    /** {@code P_IDCNAME} — 승인 호스트 토큰. */
    @Column(length = 32)
    private String idcName;

    /** 수신 판정·처리 결과. {@link CallbackOutcome}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CallbackOutcome outcome;

    /** 수신 시각. */
    private OffsetDateTime receivedAt;
}
