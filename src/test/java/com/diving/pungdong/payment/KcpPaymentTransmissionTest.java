package com.diving.pungdong.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KCP 표준결제 <b>전문(request body)</b> 사양 — 외부 호출 없이(hermetic) 돈이 걸린 판단만 고정한다.
 * 실제 HTTP 왕복은 테스트 상점ID 로 수동 검증(문서 참고).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. K* 전문 생성 / V* 검증 거부.
 *
 * <p>여기서 지키는 것: (1) 승인 금액·주문번호는 <b>서버 원본</b>이지 클라이언트 값이 아니다,
 * (2) 부분/전체 취소 구분과 KCP 필수 전문({@code mod_mny}·{@code rem_mny}), (3) KCP 금지문자 제거.
 */
class KcpPaymentTransmissionTest {

    private static final String SITE = "T0000";
    private static final String CERT = "-----BEGIN CERTIFICATE-----AAAA-----END CERTIFICATE-----";

    /* ─── K* 전문 생성 ─── */

    @Test
    @DisplayName("K1 승인 전문의 금액·주문번호는 서버 권위값이 실린다 — 결제창이 준 값을 쓰지 않는다")
    void confirmBodyUsesServerAuthoritativeValues() {
        var command = new PaymentGateway.ConfirmCommand("rnd-7-abc", 365_000, Map.of(
                "enc_data", "ENC", "enc_info", "INFO", "tran_cd", "00100000",
                // 결제창이 준 값에 다른 금액/주문번호가 섞여 있어도 전문에는 안 실린다
                "ordr_mony", "1000", "ordr_no", "attacker-order"));

        Map<String, Object> body = KcpPaymentGateway.confirmBody(SITE, CERT, command);

        assertThat(body).containsEntry("ordr_mony", 365_000)   // command.amount() = 주문의 권위 금액
                .containsEntry("ordr_no", "rnd-7-abc")
                .containsEntry("enc_data", "ENC")
                .containsEntry("enc_info", "INFO")
                .containsEntry("tran_cd", "00100000")
                .containsEntry("site_cd", SITE)
                .containsEntry("kcp_cert_info", CERT);
    }

    @Test
    @DisplayName("K2 pay_type 은 결제창이 준 값을 그대로 싣고, 없으면 카드(PACA)로 본다")
    void confirmBodyCarriesPayType() {
        Map<String, String> base = Map.of("enc_data", "E", "enc_info", "I", "tran_cd", "T");

        assertThat(KcpPaymentGateway.confirmBody(SITE, CERT,
                new PaymentGateway.ConfirmCommand("o1", 1000, base)))
                .containsEntry("pay_type", "PACA"); // 미전달 → 기본 카드

        var withBank = new java.util.HashMap<>(base);
        withBank.put("pay_type", "PABK");
        assertThat(KcpPaymentGateway.confirmBody(SITE, CERT,
                new PaymentGateway.ConfirmCommand("o1", 1000, withBank)))
                .containsEntry("pay_type", "PABK"); // 계좌이체
    }

    @Test
    @DisplayName("K3 잔액 일부만 취소하면 부분취소(STPC) + mod_mny·rem_mny 가 실린다")
    void partialCancelCarriesAmounts() {
        Map<String, Object> body = KcpPaymentGateway.cancelBody(
                SITE, CERT, "25536002322422", "SIGN", 100_000, 365_000, "수강 환불");

        assertThat(body).containsEntry("mod_type", "STPC")
                .containsEntry("mod_mny", 100_000)
                .containsEntry("rem_mny", 365_000)   // 취소 직전 잔액 — KCP 부분취소 필수
                .containsEntry("tno", "25536002322422")
                .containsEntry("kcp_sign_data", "SIGN");
    }

    @Test
    @DisplayName("K4 잔액 전부를 취소하면 전체취소(STSC) — 부분취소 전용 필드는 싣지 않는다")
    void fullCancelOmitsPartialFields() {
        Map<String, Object> body = KcpPaymentGateway.cancelBody(
                SITE, CERT, "25536002322422", "SIGN", 365_000, 365_000, "수강 환불");

        assertThat(body).containsEntry("mod_type", "STSC")
                .doesNotContainKey("mod_mny")
                .doesNotContainKey("rem_mny");
    }

    @Test
    @DisplayName("K5 KCP 금지 특수문자는 상품명·취소사유에서 제거된다 — 안 그러면 거래등록/취소가 거절된다")
    void forbiddenCharactersAreStripped() {
        var init = new PaymentGateway.InitCommand(
                "rnd-1-x", "프리다이빙 \"입문\" & 스킬, <1회차>", 200_000, "cust-1", true);

        Map<String, Object> body = KcpPaymentGateway.registerBody(SITE, "https://api.plop.cool/kcp/ret", init);

        String goodName = (String) body.get("good_name");
        assertThat(goodName).doesNotContain("\"").doesNotContain("&").doesNotContain(",")
                .doesNotContain("<").doesNotContain(">");
        assertThat(body).containsEntry("good_mny", 200_000)
                .containsEntry("ordr_idxx", "rnd-1-x")
                .containsEntry("Ret_URL", "https://api.plop.cool/kcp/ret"); // 서버 고정값
    }

    /* ─── M* 결제수단 라벨(응답 3갈래) ─── */

    @Test
    @DisplayName("M1 카드형 — PACA + card_other_pay_type 으로 어느 간편결제인지 구분한다")
    void cardTypeResolvesAffiliateBrand() throws Exception {
        assertThat(label("{\"pay_method\":\"PACA\",\"card_other_pay_type\":\"OT13\"}")).isEqualTo("카카오페이");
        assertThat(label("{\"pay_method\":\"PACA\",\"card_other_pay_type\":\"OT16\"}")).isEqualTo("네이버페이");
        assertThat(label("{\"pay_method\":\"PACA\",\"card_other_pay_type\":\"OT23\"}")).isEqualTo("토스페이");
        assertThat(label("{\"pay_method\":\"PACA\",\"card_other_pay_type\":\"OT21\"}")).isEqualTo("애플페이");
        assertThat(label("{\"pay_method\":\"PACA\"}")).isEqualTo("신용카드"); // 제휴 없이 카드 직접
    }

    @Test
    @DisplayName("M2 머니형 — PAKM/PATO/PASG 는 그 자체가 결제수단이다(card_other_pay_type 없음)")
    void moneyTypeResolvesByPayMethod() throws Exception {
        assertThat(label("{\"pay_method\":\"PAKM\"}")).isEqualTo("카카오머니");
        assertThat(label("{\"pay_method\":\"PATO\"}")).isEqualTo("토스머니");
        assertThat(label("{\"pay_method\":\"PASG\"}")).isEqualTo("SSG머니");
    }

    @Test
    @DisplayName("M3 포인트형 — PANP 는 네이버페이 포인트")
    void pointTypeResolves() throws Exception {
        assertThat(label("{\"pay_method\":\"PANP\"}")).isEqualTo("네이버페이포인트");
    }

    @Test
    @DisplayName("M4 모르는 코드는 원문을 유지한다 — 삼켜서 추적 불가가 되지 않게")
    void unknownCodeKeptAsIs() throws Exception {
        assertThat(label("{\"pay_method\":\"PZZZ\"}")).isEqualTo("PZZZ");
    }

    private static String label(String json) throws Exception {
        return KcpPaymentGateway.methodLabel(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    }

    /* ─── V* 검증 거부 ─── */

    @Test
    @DisplayName("V1 승인에 필요한 PG 값(enc_data 등)이 빠지면 400 — 전문을 만들지 않는다")
    void missingPgPayloadRejected() {
        var command = new PaymentGateway.ConfirmCommand("o1", 1000, Map.of("enc_data", "E")); // enc_info/tran_cd 없음

        assertThatThrownBy(() -> KcpPaymentGateway.confirmBody(SITE, CERT, command))
                .isInstanceOf(com.diving.pungdong.global.advice.exception.BadRequestException.class);
    }

    @Test
    @DisplayName("V2 pgPayload 가 아예 없어도 400 — null 로 통과하지 않는다")
    void nullPgPayloadRejected() {
        var command = new PaymentGateway.ConfirmCommand("o1", 1000, null);

        assertThatThrownBy(() -> KcpPaymentGateway.confirmBody(SITE, CERT, command))
                .isInstanceOf(com.diving.pungdong.global.advice.exception.BadRequestException.class);
    }
}
