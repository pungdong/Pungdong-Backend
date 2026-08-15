package com.diving.pungdong.payment;

import com.diving.pungdong.global.advice.exception.BadRequestException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 결제 승인 경계 — BE 와 외부 PG 사이. {@code address}/{@code consent}/{@code identityverification} 과 같은
 * "interface + 구현 교체" 패턴이되, <b>선택 방식이 다르다</b>: 구현은 전부 빈으로 등록되고
 * {@link PaymentGatewayRegistry} 가 고른다(신규 결제=전역 설정, 기존 주문=주문에 박제된 provider).
 *
 * <ul>
 *   <li>{@link StubPaymentGateway} — 외부 미호출, 즉시 승인. 기본값(로컬/테스트).</li>
 *   <li>{@link TossPaymentGateway} — 토스페이먼츠 결제위젯 v2({@code mode=toss}).</li>
 *   <li>{@link InicisPaymentGateway} — KG이니시스 INIpay PRO 표준결제({@code mode=inicis}).</li>
 * </ul>
 *
 * <p><b>왜 PG 중립인가</b>: 토스 심사가 밀려(2026-07) 이니시스로 전환하면서, PG 를 갈아끼우는 비용을
 * "어댑터 1개 + config 한 줄"로 묶어두기 위해. 토스↔이니시스는 {@code mode} 만 바꿔 스왑하고(플러그식),
 * PG 고유 어휘(paymentKey/DONE / P_TID/P_STATUS)는 어댑터 안에 가둔다.
 *
 * <p><b>공통 불변식</b>: 승인 금액은 <b>서버 권위 금액</b>({@code amount})이다 — FE 가 보낸 값이 아니라
 * {@link PaymentOrder} 에 박힌 값을 그대로 PG 에 넘겨 대조시킨다. 시크릿(토스 시크릿키 / 이니시스 hashKey·apiKey)은 BE 밖으로 안 나간다.
 */
public interface PaymentGateway {

    /** 이 게이트웨이가 붙은 PG. FE 분기 근거로 prepare 응답에 실린다. */
    PaymentProvider provider();

    /**
     * 결제창 구동값 — FE 가 이 PG 의 결제창을 띄우는 데 필요한 값들. PG 마다 키가 다르므로 맵으로 전달한다
     * (한 번에 한 PG 만 살아있고, FE 는 {@link #provider()} 로 분기하므로 타입을 고정할 실익이 없다).
     *
     * <ul>
     *   <li>토스 — {@code clientKey}(공개), {@code customerKey}. 외부 호출 없음.</li>
     *   <li>이니시스 — P_ 파라미터({@code P_MID}·{@code P_OID}·{@code P_AMT}·서명 {@code P_CHKFAKE} 등). 외부 호출 없이
     *       계산만 하고, FE 가 {@code INIPayPro_v2.js} 로 결제창을 띄운다.</li>
     * </ul>
     */
    Map<String, String> initParams(InitCommand command);

    /**
     * 결제 승인 — PG 에 최종 승인을 요청한다. 거절(금액 불일치·이미 처리·잘못된 키 등)이면
     * {@link BadRequestException}. 재시도 안전성(멱등)은 각 어댑터가 PG 규약대로 보장한다.
     */
    ConfirmResult confirm(ConfirmCommand command);

    /**
     * 결제 취소(환불) — {@code cancelAmount} 로 <b>부분 취소</b>. {@code pgTransactionId} 는 승인 때 받아
     * 저장해 둔 PG 거래 식별자(토스 {@code paymentKey} / 이니시스 {@code P_TID}).
     *
     * @param remainingAmount 이 취소 <b>직전</b>의 취소가능잔액(= 승인액 − 기취소액). 이니시스 부분취소는 이 값에서
     *                        {@code cancelAmount} 를 뺀 "취소 후 잔액"({@code confirmPrice})으로 변환해 쓴다. 토스는 무시한다.
     * @param originalAmount  이 거래의 <b>승인액(원금)</b>. {@code remainingAmount < originalAmount} 면 이미 부분취소 이력이
     *                        있는 거래다 — 이니시스는 그런 거래에 전체취소(refund)를 거부하므로(500624) 잔액 전액이라도
     *                        부분취소(partialRefund, confirmPrice=0)로 보내야 한다. 우리 원장이 아는 사실을 어댑터에
     *                        넘겨 전문을 처음부터 맞게 고른다(PG 거절로 알아내지 않는다). 토스/스텁은 무시한다.
     */
    CancelResult cancel(String pgTransactionId, int cancelAmount, int remainingAmount, int originalAmount, String reason);

    /* ─── 명령/결과 ─── */

    /**
     * 결제창 구동 요청. {@code mobile} 은 이니시스 {@code P_DEVICE_TYPE}(WEB/MOBILE) 분기에 쓰인다.
     *
     * <p>⚠️ 리턴 URL 은 <b>여기 없다</b> — 클라이언트가 정하면 오픈 리다이렉트가 되므로 BE 설정값으로 고정한다.
     */
    record InitCommand(String orderId, String orderName, int amount, String customerKey, boolean mobile) {
    }

    /**
     * 승인 요청. {@code amount} 는 서버 권위 금액, {@code pgPayload} 는 결제창이 콜백으로 돌려준 PG 고유 인증값
     * (토스: {@code paymentKey} / 이니시스: {@code P_AUTH_TID}·{@code P_IDCNAME}).
     */
    record ConfirmCommand(String orderId, int amount, Map<String, String> pgPayload) {

        /** 필수 PG 값 꺼내기 — 없으면 잘못된 요청(400). 어댑터가 자기 필드를 자기 이름으로 요구한다. */
        public String require(String key) {
            String v = pgPayload == null ? null : pgPayload.get(key);
            if (v == null || v.isBlank()) {
                throw new BadRequestException();
            }
            return v;
        }
    }

    /**
     * 승인 결과. {@code approved} 는 PG 별 성공 표현(토스 {@code status=DONE} / 이니시스 {@code P_STATUS=00})을
     * <b>어댑터가 정규화</b>한 값 — 서비스 계층에 PG 어휘가 새지 않게 한다.
     *
     * @param pgTransactionId 이후 취소에 쓰는 PG 거래 식별자(토스 paymentKey / 이니시스 P_TID). 이니시스는 승인 응답으로만
     *                        알 수 있어 결과에 포함한다.
     */
    record ConfirmResult(boolean approved, String rawStatus, String method,
                         OffsetDateTime approvedAt, String receiptUrl, String pgTransactionId) {
    }

    /** 취소 결과 — {@code canceled} 는 어댑터가 정규화(토스 CANCELED/PARTIAL_CANCELED / 이니시스 resultCode=00). */
    record CancelResult(boolean canceled, String rawStatus, OffsetDateTime canceledAt) {
    }
}
