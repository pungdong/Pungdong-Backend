package com.diving.pungdong.usecase;

import com.diving.pungdong.venue.sync.OfficialVenueReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sanity 웹훅 use-case — HMAC 검증 + 중복 차단. reconcile 은 {@code @MockBean} 으로 호출 여부만 확인.
 * 시크릿은 프로퍼티로 주입(test 기본은 빈 값 = fail-closed).
 *
 * <p>W1 유효서명→200+reconcile · W2 위조서명→401+reconcile 안 함 · W3 재전송→1회만 처리(dedup).
 */
@SpringBootTest(properties = "pungdong.venue.webhook.secret=test-webhook-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SanityWebhookUseCaseTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String PATH = "/webhooks/sanity/venue";

    @Autowired MockMvc mockMvc;
    @MockBean OfficialVenueReconciler reconciler;

    /** Sanity 형식 서명 헤더 생성: t=<ts>,v1=base64url(HMAC_SHA256(secret,"<ts>.<body>")). */
    private String sign(String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
        String v1 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        return "t=" + ts + ",v1=" + v1;
    }

    @Test
    @DisplayName("W1 유효한 서명이면 200 + reconcile 1회")
    void w1_valid_signature() throws Exception {
        String body = "{\"_type\":\"venue\",\"_id\":\"official-x\"}";
        mockMvc.perform(post(PATH)
                        .header("sanity-webhook-signature", sign("1700000001", body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(reconciler, times(1)).reconcile();
    }

    @Test
    @DisplayName("W2 서명이 위조면 401 + reconcile 호출 안 함")
    void w2_forged_signature() throws Exception {
        String body = "{\"_type\":\"venue\",\"_id\":\"official-y\"}";
        mockMvc.perform(post(PATH)
                        .header("sanity-webhook-signature", "t=1700000002,v1=forged")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        verify(reconciler, never()).reconcile();
    }

    @Test
    @DisplayName("W3 같은 delivery 재전송이면 둘 다 200 이지만 reconcile 은 1회만(dedup)")
    void w3_replay_deduped() throws Exception {
        String body = "{\"_type\":\"venue\",\"_id\":\"official-z\"}";
        String header = sign("1700000003", body);

        mockMvc.perform(post(PATH).header("sanity-webhook-signature", header)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(PATH).header("sanity-webhook-signature", header)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(reconciler, times(1)).reconcile();
    }
}
