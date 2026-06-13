package com.diving.pungdong.venue.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.sync.SanityVenueClient.OfficialVenueDoc;
import com.diving.pungdong.venue.sync.SanityVenueClient.RevEntry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공식(OFFICIAL) 위치의 Redis 캐시. 읽기는 <b>cache-aside</b> — 비어 있으면 Sanity 에서 lazy-load
 * (cold start·테스트에서도 동작). 신선도는 {@link OfficialVenueReconciler}(read-side {@code _rev}
 * 대조) + 웹훅이 유지하며 <b>TTL 은 두지 않는다</b>(venue.md 결정 — 안 바뀐 전체 재fetch 낭비 회피).
 *
 * <p>Redis 키: {@code venue:official:ids}(SET) · {@code :doc:<id>}(GROQ JSON) · {@code :rev:<id>} ·
 * {@code venue:official:loaded}(적재 마커) · {@code venue:official:reconcileAt}(heartbeat epoch ms).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialVenueCache {

    private static final String IDS = "venue:official:ids";
    private static final String DOC = "venue:official:doc:";
    private static final String REV = "venue:official:rev:";
    private static final String LOADED = "venue:official:loaded";
    private static final String RECONCILE_AT = "venue:official:reconcileAt";

    private final RedisTemplate<String, String> redisTemplate;
    private final SanityVenueClient sanityVenueClient;
    private final ObjectMapper objectMapper;

    /** 공식 위치 전량을 통합 DTO 로. 캐시가 비어 있으면 Sanity 에서 lazy-load 후 반환. */
    public List<VenueResponse> getAll() {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(LOADED))) {
            loadFromSource();
        }
        List<VenueResponse> out = new ArrayList<>();
        Set<String> ids = redisTemplate.opsForSet().members(IDS);
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            String json = redisTemplate.opsForValue().get(DOC + id);
            String rev = redisTemplate.opsForValue().get(REV + id);
            if (json != null) {
                out.add(OfficialVenueMapper.toResponse(new OfficialVenueDoc(id, rev, parse(json))));
            }
        }
        return out;
    }

    /** 첫 적재(lazy) — fetchAll → 캐시 교체 + heartbeat. */
    void loadFromSource() {
        replaceAll(sanityVenueClient.fetchAll());
    }

    /** 캐시 전량 교체(reconcile·webhook 가 변경 감지 시 호출). 작은 카탈로그라 통째 교체가 단순·안전. */
    @SneakyThrows
    public void replaceAll(List<OfficialVenueDoc> docs) {
        Set<String> oldIds = redisTemplate.opsForSet().members(IDS);
        if (oldIds != null) {
            for (String id : oldIds) {
                redisTemplate.delete(DOC + id);
                redisTemplate.delete(REV + id);
            }
        }
        redisTemplate.delete(IDS);
        for (OfficialVenueDoc d : docs) {
            redisTemplate.opsForValue().set(DOC + d.getId(), objectMapper.writeValueAsString(d.getDoc()));
            if (d.getRev() != null) {
                redisTemplate.opsForValue().set(REV + d.getId(), d.getRev());
            }
            redisTemplate.opsForSet().add(IDS, d.getId());
        }
        redisTemplate.opsForValue().set(LOADED, "1");
        log.info("[venue-sync] official venue cache replaced — {} venue(s)", docs.size());
    }

    /** 현재 캐시된 {@code id→_rev} (reconcile 대조용). */
    public Map<String, String> cachedRevs() {
        Map<String, String> out = new HashMap<>();
        Set<String> ids = redisTemplate.opsForSet().members(IDS);
        if (ids != null) {
            for (String id : ids) {
                out.put(id, redisTemplate.opsForValue().get(REV + id));
            }
        }
        return out;
    }

    public boolean isLoaded() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOADED));
    }

    /** 공식 위치 id 가 캐시(=Sanity)에 존재하는가 — 장비 가격표가 official 참조를 검증할 때. */
    public boolean contains(String officialId) {
        if (!isLoaded()) {
            loadFromSource();
        }
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(IDS, officialId));
    }

    /** reconcile 성공 시각(heartbeat) 기록 — liveness 판정용. */
    public void markReconciled(long epochMillis) {
        redisTemplate.opsForValue().set(RECONCILE_AT, String.valueOf(epochMillis));
    }

    /** 마지막 reconcile 성공 시각(epoch ms), 없으면 null. */
    public Long lastReconciledAt() {
        String v = redisTemplate.opsForValue().get(RECONCILE_AT);
        return v == null ? null : Long.parseLong(v);
    }

    @SneakyThrows
    private JsonNode parse(String json) {
        return objectMapper.readTree(json);
    }
}
