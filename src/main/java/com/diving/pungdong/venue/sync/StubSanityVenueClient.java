package com.diving.pungdong.venue.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 테스트용 {@link SanityVenueClient} — 실 Sanity 대신 고정 공식 위치 2건을 돌려준다(StubAddressApiClient
 * 패턴). 코스 빌더 머지/리컨사일/종목 필터를 실 외부 호출 없이 검증하기 위함.
 *
 * <ul>
 *   <li>딥스테이션 — DEEP_POOL, FREEDIVING·SCUBA, 평일 FIXED + 주말 SAME</li>
 *   <li>양양 비치 — OCEAN, SCUBA, 평일 OPEN(키반납 3h) + 주말 미판매</li>
 * </ul>
 *
 * {@code @Profile("test")} — 운영/로컬은 {@link HttpSanityVenueClient}.
 */
@Slf4j
@Component
@Profile("test")
@RequiredArgsConstructor
public class StubSanityVenueClient implements SanityVenueClient {

    static final String DEEPSTATION_ID = "official-deepstation";
    static final String YANGYANG_ID = "official-yangyang";

    private final ObjectMapper objectMapper;

    @SneakyThrows
    private JsonNode deepstation() {
        return objectMapper.readTree("{"
                + "\"_id\":\"" + DEEPSTATION_ID + "\",\"_rev\":\"rev-deep-1\","
                + "\"name\":\"딥스테이션\",\"type\":\"DEEP_POOL\",\"maxDepth\":36,"
                + "\"address\":\"경기도 용인시 처인구 포곡읍 성산로 523\",\"addressDetail\":\"딥스테이션\","
                + "\"latitude\":37.25,\"longitude\":127.21,\"equipInfo\":\"풀세트 입장료 포함\","
                + "\"closures\":[{\"type\":\"WEEKLY\",\"weekdays\":[\"MONDAY\"]}],"
                + "\"tickets\":[{\"name\":\"일반권\",\"disciplines\":[\"FREEDIVING\",\"SCUBA\"],"
                + "\"weekday\":{\"sold\":true,\"fee\":48000,\"timeMode\":\"FIXED\",\"blocks\":[{\"start\":\"06:00\",\"end\":\"09:00\"}]},"
                + "\"weekend\":{\"sold\":true,\"fee\":55000,\"timeMode\":\"SAME\"}}]"
                + "}");
    }

    @SneakyThrows
    private JsonNode yangyang() {
        return objectMapper.readTree("{"
                + "\"_id\":\"" + YANGYANG_ID + "\",\"_rev\":\"rev-yang-1\","
                + "\"name\":\"양양 비치 포인트\",\"type\":\"OCEAN\",\"maxDepth\":null,"
                + "\"address\":\"강원특별자치도 양양군 현남면\",\"addressDetail\":null,"
                + "\"latitude\":38.0,\"longitude\":128.6,\"equipInfo\":null,"
                + "\"closures\":[],"
                + "\"tickets\":[{\"name\":\"종일권\",\"disciplines\":[\"SCUBA\"],"
                + "\"weekday\":{\"sold\":true,\"fee\":30000,\"timeMode\":\"OPEN\",\"open\":\"09:00\",\"close\":\"18:00\",\"holdHours\":3},"
                + "\"weekend\":{\"sold\":false}}]"
                + "}");
    }

    @Override
    public List<OfficialVenueDoc> fetchAll() {
        return List.of(
                new OfficialVenueDoc(DEEPSTATION_ID, "rev-deep-1", deepstation()),
                new OfficialVenueDoc(YANGYANG_ID, "rev-yang-1", yangyang()));
    }

    @Override
    public List<RevEntry> fetchRevs() {
        return List.of(
                new RevEntry(DEEPSTATION_ID, "rev-deep-1"),
                new RevEntry(YANGYANG_ID, "rev-yang-1"));
    }
}
