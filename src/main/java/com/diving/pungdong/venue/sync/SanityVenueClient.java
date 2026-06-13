package com.diving.pungdong.venue.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 공식(OFFICIAL) 위치를 Sanity 에서 서버사이드로 읽는 경계. consent 의 {@code SanityTermClient} 와
 * 동일한 "interface + 환경별 구현 교체" 패턴 — 운영은 {@link HttpSanityVenueClient}, 테스트는
 * {@link StubSanityVenueClient}({@code @Profile}).
 *
 * <p>왜 BE 가 official 을 또 읽나: 공개 표시는 FE 가 Sanity CDN 직접 읽지만, 코스 저장/availability/
 * 부킹은 BE 가 클라 제출값을 믿지 않고 official 운영데이터(시간·휴무·입장료)를 직접 읽어 검증해야 한다.
 * 그래서 BE 가 Redis 에 캐싱하고(read-side {@code _rev} 대조로 정합성 유지), 코스 빌더 통합 목록도
 * 같은 캐시 뷰에서 낸다. 상세 docs/features/venue.md "캐싱·동기화·모니터링 설계".
 */
public interface SanityVenueClient {

    /** 공식 위치 전량(활성) — 캐시 적재용. doc 은 GROQ 결과 노드(_id/_rev 포함). */
    List<OfficialVenueDoc> fetchAll();

    /** 모든 위치의 리비전 토큰만(활성 무관) — reconcile 의 싸고 정확한 "변경 감지" 게이트. */
    List<RevEntry> fetchRevs();

    /** Sanity 위치 1건의 원본 GROQ 결과. id = Sanity {@code _id}, rev = {@code _rev}. */
    final class OfficialVenueDoc {
        private final String id;
        private final String rev;
        private final JsonNode doc;

        public OfficialVenueDoc(String id, String rev, JsonNode doc) {
            this.id = id;
            this.rev = rev;
            this.doc = doc;
        }

        public String getId() { return id; }
        public String getRev() { return rev; }
        public JsonNode getDoc() { return doc; }
    }

    /** {@code {_id, _rev}} 한 쌍 — reconcile 대조용. */
    final class RevEntry {
        private final String id;
        private final String rev;

        public RevEntry(String id, String rev) {
            this.id = id;
            this.rev = rev;
        }

        public String getId() { return id; }
        public String getRev() { return rev; }
    }
}
