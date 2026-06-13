package com.diving.pungdong.venue.sync;

import com.diving.pungdong.venue.sync.SanityVenueClient.RevEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 공식 위치 캐시의 정합성 바닥 — read-side {@code _rev} 대조 reconcile. 싼 {@code venueRevs} 질의로
 * 전체 {@code _id,_rev} 만 받아 캐시와 대조하고, <b>다르면(추가·변경·삭제)</b> 전량 refetch 해 교체한다.
 * full-set 비교라 3종(추가·변경·삭제)이 모두 잡히고, 비용은 리비전 토큰 바이트 단위(TTL 전체 재fetch 폐기).
 *
 * <p>스케줄 트리거는 {@link OfficialVenueReconcileScheduler}({@code @Profile("!test")}); 이 클래스의
 * {@link #reconcile()} 는 프로파일 무관이라 테스트가 직접 호출해 분기를 검증한다. 웹훅도 이걸 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialVenueReconciler {

    private final SanityVenueClient sanityVenueClient;
    private final OfficialVenueCache cache;

    /** 한 차례 대조 + (변경 시) 교체 + heartbeat 기록. 예외는 호출부(스케줄러/웹훅)가 처리. */
    public synchronized void reconcile() {
        if (!cache.isLoaded()) {
            cache.loadFromSource();
            cache.markReconciled(System.currentTimeMillis());
            log.info("[venue-sync] reconcile — initial load");
            return;
        }
        Map<String, String> remote = new HashMap<>();
        for (RevEntry e : sanityVenueClient.fetchRevs()) {
            remote.put(e.getId(), e.getRev());
        }
        if (changed(remote, cache.cachedRevs())) {
            List<SanityVenueClient.OfficialVenueDoc> docs = sanityVenueClient.fetchAll();
            cache.replaceAll(docs);
            log.info("[venue-sync] reconcile — change detected, refetched {} venue(s)", docs.size());
        }
        cache.markReconciled(System.currentTimeMillis());
    }

    /** id 집합이 다르거나(추가·삭제) 같은 id 의 _rev 가 다르면(변경) true. */
    private boolean changed(Map<String, String> remote, Map<String, String> cached) {
        if (!remote.keySet().equals(cached.keySet())) {
            return true;
        }
        for (Map.Entry<String, String> e : remote.entrySet()) {
            if (!java.util.Objects.equals(e.getValue(), cached.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }
}
