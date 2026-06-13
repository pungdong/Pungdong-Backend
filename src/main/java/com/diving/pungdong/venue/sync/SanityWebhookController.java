package com.diving.pungdong.venue.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Sanity GROQ 웹훅 수신 — 어드민이 공식 위치를 publish 하면 거의 즉시 캐시를 갱신(지연 최적화).
 * 정합성의 <b>조건이 아니라</b> "≤reconcile주기"를 "≤수초"로 줄이는 최적화 — 웹훅이 유실/막혀도
 * reconcile 가 따라잡는다(venue.md).
 *
 * <p>흐름: HMAC 검증({@link SanityWebhookVerifier}) → 같은 delivery 중복 차단(Redis dedup) →
 * reconcile(변경 감지+refetch, best-effort) → <b>항상 즉시 2xx</b>. 검증 실패만 401.
 * write-back ack 안 함(read-side _rev 대조가 상위호환).
 *
 * <p>매처 {@code POST /webhooks/sanity/venue} → permitAll(JWT 아닌 HMAC 으로 인증).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SanityWebhookController {

    private static final String DEDUP_KEY = "venue:webhook:seen:";
    private static final long DEDUP_TTL_MIN = 5;

    private final SanityWebhookVerifier verifier;
    private final OfficialVenueReconciler reconciler;
    private final RedisTemplate<String, String> redisTemplate;

    @PostMapping("/webhooks/sanity/venue")
    public ResponseEntity<?> onVenuePublished(
            @RequestHeader(value = "sanity-webhook-signature", required = false) String signature,
            @RequestBody(required = false) String body) {

        String deliveryId = verifier.verify(signature, body);
        if (deliveryId == null) {
            return ResponseEntity.status(401).build();
        }

        // 같은 delivery 재전송(타임아웃 재시도 등)은 한 번만 처리 — reconcile 자체도 idempotent 라 안전망.
        Boolean firstSeen = redisTemplate.opsForValue()
                .setIfAbsent(DEDUP_KEY + deliveryId, "1", DEDUP_TTL_MIN, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(firstSeen)) {
            return ResponseEntity.ok().build();
        }

        try {
            reconciler.reconcile();
        } catch (RuntimeException e) {
            // 즉시 2xx ack — 처리 실패는 주기 reconcile 가 회복(웹훅은 지연 최적화일 뿐).
            log.warn("[venue-sync] webhook reconcile 실패 — 다음 주기 reconcile 가 회복", e);
        }
        return ResponseEntity.ok().build();
    }
}
