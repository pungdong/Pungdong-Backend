package com.diving.pungdong.venue.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Sanity GROQ 웹훅 서명 검증. 헤더 {@code sanity-webhook-signature: t=<ts>,v1=<sig>} 에서 sig 는
 * {@code base64url(HMAC_SHA256(secret, "<ts>.<body>"))}(패딩 없음). 시크릿 미설정이면 검증 불가 →
 * fail-closed(거부). 정합성은 reconcile 가 보장하므로 웹훅이 막혀도 신선도는 ≤reconcile주기로 수렴.
 */
@Slf4j
@Component
public class SanityWebhookVerifier {

    private final String secret;

    public SanityWebhookVerifier(@Value("${pungdong.venue.webhook.secret:}") String secret) {
        this.secret = secret;
    }

    /** 서명 헤더의 v1 값(중복 처리 dedup 키로도 씀). 검증 실패면 null. */
    public String verify(String signatureHeader, String body) {
        if (!StringUtils.hasText(secret)) {
            log.warn("[venue-sync] webhook secret 미설정 — 웹훅 거부(reconcile 로만 신선도 유지)");
            return null;
        }
        if (!StringUtils.hasText(signatureHeader) || !StringUtils.hasText(body)) {
            return null;
        }
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                ts = kv[1];
            } else if ("v1".equals(kv[0])) {
                v1 = kv[1];
            }
        }
        if (ts == null || v1 == null) {
            return null;
        }
        String expected = hmac(ts + "." + body);
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        return ok ? v1 : null;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }
}
