package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.equipment.VenueEquipmentExtensionJpaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.data.redis.core.RedisTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대여 장비 가격표(venue-extension) use-case — 강사×위치 가격표 upsert/조회 + 위치 참조 검증 + 사이즈 형식
 * + venue 기본 장비 prefill fallback(저장분 없는 OFFICIAL 위치 → Sanity defaultEquipment 합성).
 * 실 H2 + 임베디드 Redis + 시큐리티, Sanity 는 stub(official id = official-deepstation, 기본 장비 4종 /
 * official-yangyang, 기본 장비 없음).
 *
 * <p>E* = 저장/조회/사이즈, S* = venue 기본 장비 prefill(fallback), V* = 검증(참조 거절·lenient 파싱),
 * R* = 소유 격리, C* = 캐시 스키마 버전.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VenueEquipmentUseCaseTest {

    private static final String OFFICIAL_DEEPSTATION = "OFFICIAL:official-deepstation";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired VenueEquipmentExtensionJpaRepo extensionRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;

    /** 공식 위치 캐시는 임베디드 Redis 에 process-전역으로 남는다 — 다른 테스트(@MockBean 리컨사일)의
     * 오염을 받지 않도록 매 테스트 비워 stub 에서 새로 lazy-load 하게 한다. */
    @BeforeEach
    void flushOfficialCache() {
        Set<String> keys = redisTemplate.keys("venue:official:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void cleanUp() {
        extensionRepo.deleteAll();
        venueRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private Account account(String email) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(email.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private Venue seedCustom(Account owner) {
        return venueRepo.save(Venue.builder()
                .owner(owner).name("내 죽도 포인트").type(VenueType.OCEAN).lockedDisciplineCode("FREEDIVING")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private String body(String venueRefId, List<Map<String, Object>> items) throws Exception {
        return objectMapper.writeValueAsString(Map.of("venueRefId", venueRefId, "items", items));
    }

    private String putEquip(Account me, String json) throws Exception {
        return mockMvc.perform(put("/venue-equipment")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
    }

    /* ════════════════ E — 저장/조회/사이즈 ════════════════ */

    @Test
    @DisplayName("E1 내 커스텀 위치에 장비 가격표 저장 — 사이즈 형식 프리셋이 자동으로 채워진다")
    void e1_upsert_custom_with_size_presets() throws Exception {
        Account me = account("e1@pungdong.com");
        String ref = "CUSTOM:" + seedCustom(me).getId();
        String json = body(ref, List.of(
                Map.of("name", "롱핀", "price", 5000, "sizeFormat", "SHOE_MM"),
                Map.of("name", "마스크·스노클", "price", 0, "sizeFormat", "NONE"),
                Map.of("name", "슈트", "price", 3000, "sizeFormat", "APPAREL_SXL")));

        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());

        mockMvc.perform(get("/venue-equipment").param("venueRefId", ref)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].venueRefId").value(ref))
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(3)))
                // 롱핀: SHOE_MM 프리셋 자동
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].sizeOptions").value(hasItem("250")))
                // 마스크: NONE → 빈 옵션
                .andExpect(jsonPath("$._embedded.extensions[0].items[1].sizeOptions").value(hasSize(0)))
                // 슈트: APPAREL_SXL 프리셋 자동(XS~XXL)
                .andExpect(jsonPath("$._embedded.extensions[0].items[2].sizeOptions").value(hasItem("L")))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].price").value(5000));
    }

    @Test
    @DisplayName("E2 같은 위치에 다시 저장하면 items 가 전량 교체된다(스냅샷)")
    void e2_upsert_replaces_items() throws Exception {
        Account me = account("e2@pungdong.com");
        String ref = "CUSTOM:" + seedCustom(me).getId();
        putEquip(me, body(ref, List.of(
                Map.of("name", "롱핀", "price", 5000, "sizeFormat", "SHOE_MM"),
                Map.of("name", "슈트", "price", 3000, "sizeFormat", "APPAREL_SXL"))));

        // 교체: 1개만
        putEquip(me, body(ref, List.of(Map.of("name", "마스크", "price", 0, "sizeFormat", "NONE"))));

        mockMvc.perform(get("/venue-equipment").param("venueRefId", ref)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].name").value("마스크"));
    }

    @Test
    @DisplayName("E3 공식(OFFICIAL) 위치에도 가격표를 저장할 수 있다")
    void e3_upsert_official() throws Exception {
        Account me = account("e3@pungdong.com");
        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(OFFICIAL_DEEPSTATION, List.of(
                                Map.of("name", "롱핀", "price", 7000, "sizeFormat", "SHOE_MM")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueRefId").value(OFFICIAL_DEEPSTATION));
    }

    /* ════════════════ S — venue 기본 장비 prefill (fallback) ════════════════ */

    @Test
    @DisplayName("S1 저장분 없는 OFFICIAL 위치 조회 — venue 기본 장비가 source=VENUE_DEFAULT 로 합성돼 온다(id null·sizeOptions null=자동)")
    void s1_official_without_row_returns_venue_default() throws Exception {
        Account me = account("s1@pungdong.com");

        mockMvc.perform(get("/venue-equipment").param("venueRefId", OFFICIAL_DEEPSTATION)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].source").value("VENUE_DEFAULT"))
                .andExpect(jsonPath("$._embedded.extensions[0].id").value(nullValue()))
                .andExpect(jsonPath("$._embedded.extensions[0].venueRefId").value(OFFICIAL_DEEPSTATION))
                // stub 은 5행이지만 price 누락(보온조끼)은 skip — "미상"을 0원으로 둔갑시키지 않는다
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(4)))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].id").value(nullValue()))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].name").value("롱핀"))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].price").value(5000))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].sizeFormat").value("SHOE_MM"))
                // sizeOptions 는 null(= "자동") — [] 는 FE 가 "0개 선택"으로 렌더해 표시≠저장(계약서 §3 개정)
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].sizeOptions").value(nullValue()))
                .andExpect(jsonPath("$._embedded.extensions[0].items[1].name").value("슈트"))
                .andExpect(jsonPath("$._embedded.extensions[0].items[1].sizeFormat").value("APPAREL_SXL"));
    }

    @Test
    @DisplayName("S2 강사가 PUT 으로 저장한 뒤에는 같은 조회가 source=MINE 저장분으로 온다(기본값 대체)")
    void s2_after_put_source_is_mine() throws Exception {
        Account me = account("s2@pungdong.com");
        putEquip(me, body(OFFICIAL_DEEPSTATION, List.of(
                Map.of("name", "롱핀", "price", 7000, "sizeFormat", "SHOE_MM"))));

        mockMvc.perform(get("/venue-equipment").param("venueRefId", OFFICIAL_DEEPSTATION)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].source").value("MINE"))
                .andExpect(jsonPath("$._embedded.extensions[0].id").value(notNullValue()))
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.extensions[0].items[0].price").value(7000));
    }

    @Test
    @DisplayName("S3 강사가 items 를 비워 저장하면 빈 목록 그대로 MINE — venue 기본값이 부활하지 않는다")
    void s3_emptied_row_does_not_revive_defaults() throws Exception {
        Account me = account("s3@pungdong.com");
        putEquip(me, body(OFFICIAL_DEEPSTATION, List.of()));

        mockMvc.perform(get("/venue-equipment").param("venueRefId", OFFICIAL_DEEPSTATION)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].source").value("MINE"))
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(0)));
    }

    @Test
    @DisplayName("S4 기본 장비가 없는 OFFICIAL 위치(양양)는 기존처럼 빈 컬렉션이다")
    void s4_official_without_defaults_stays_empty() throws Exception {
        Account me = account("s4@pungdong.com");

        mockMvc.perform(get("/venue-equipment").param("venueRefId", "OFFICIAL:official-yangyang")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions").doesNotExist());
    }

    @Test
    @DisplayName("S5 CUSTOM 위치는 저장분이 없으면 기존처럼 빈 컬렉션이다(기본값 합성 없음)")
    void s5_custom_without_row_stays_empty() throws Exception {
        Account me = account("s5@pungdong.com");
        String ref = "CUSTOM:" + seedCustom(me).getId();

        mockMvc.perform(get("/venue-equipment").param("venueRefId", ref)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions").doesNotExist());
    }

    @Test
    @DisplayName("S6 무필터 GET(전체 목록)에는 venue 기본값이 섞이지 않는다 — 저장분 없으면 빈 컬렉션")
    void s6_unfiltered_list_has_no_fallback() throws Exception {
        Account me = account("s6@pungdong.com");

        mockMvc.perform(get("/venue-equipment")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions").doesNotExist());
    }

    /* ════════════════ V — 위치 참조 검증 ════════════════ */

    @Test
    @DisplayName("V1 남의 커스텀 위치 참조로 저장하면 400")
    void v1_custom_not_owned() throws Exception {
        Account me = account("v1@pungdong.com");
        Account other = account("v1-other@pungdong.com");
        String othersRef = "CUSTOM:" + seedCustom(other).getId();

        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(othersRef, List.of(Map.of("name", "롱핀", "price", 5000)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 캐시에 없는 공식 위치 / 형식이 깨진 토큰이면 400")
    void v2_official_missing_or_malformed() throws Exception {
        Account me = account("v2@pungdong.com");
        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OFFICIAL:does-not-exist", List.of(Map.of("name", "롱핀", "price", 0)))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("garbage-no-prefix", List.of(Map.of("name", "롱핀", "price", 0)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V3 기본 장비의 미상/누락 sizeFormat 은 null 로 lenient 파싱된다 — enum crash(500) 없음")
    void v3_unknown_size_format_is_null() throws Exception {
        Account me = account("v3@pungdong.com");

        mockMvc.perform(get("/venue-equipment").param("venueRefId", OFFICIAL_DEEPSTATION)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                // 마스크·스노클: sizeFormat 누락 → null (FE guessSizeFormat 몫)
                .andExpect(jsonPath("$._embedded.extensions[0].items[2].name").value("마스크·스노클"))
                .andExpect(jsonPath("$._embedded.extensions[0].items[2].sizeFormat").value(nullValue()))
                // 웨이트: 미상 문자열 "KG_BELT" → null (거절·크래시 아님)
                .andExpect(jsonPath("$._embedded.extensions[0].items[3].name").value("웨이트"))
                .andExpect(jsonPath("$._embedded.extensions[0].items[3].sizeFormat").value(nullValue()));
    }

    /* ════════════════ C — 캐시 스키마 버전 ════════════════ */

    @Test
    @DisplayName("C1 구버전 코드가 남긴 캐시(버전 마커 없음·defaultEquipment 없는 문서)는 읽기 시 lazy reload 되어 기본 장비가 나온다")
    void c1_stale_schema_cache_reloads_lazily() throws Exception {
        // 구버전 코드가 적재한 캐시 시뮬레이션: loaded=1 인데 스키마 버전 마커가 없고 문서에 defaultEquipment 가 없다.
        redisTemplate.opsForSet().add("venue:official:ids", "official-deepstation");
        redisTemplate.opsForValue().set("venue:official:doc:official-deepstation",
                "{\"_id\":\"official-deepstation\",\"name\":\"딥스테이션\",\"type\":\"DEEP_POOL\"}");
        redisTemplate.opsForValue().set("venue:official:rev:official-deepstation", "rev-deep-0");
        redisTemplate.opsForValue().set("venue:official:loaded", "1");

        Account me = account("c1@pungdong.com");
        mockMvc.perform(get("/venue-equipment").param("venueRefId", OFFICIAL_DEEPSTATION)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions[0].source").value("VENUE_DEFAULT"))
                .andExpect(jsonPath("$._embedded.extensions[0].items").value(hasSize(4)));
    }

    /* ════════════════ R — 소유 격리 ════════════════ */

    @Test
    @DisplayName("R1 내 가격표 목록엔 남의 가격표가 안 보인다")
    void r1_isolation() throws Exception {
        Account me = account("r1@pungdong.com");
        Account other = account("r1-other@pungdong.com");
        putEquip(other, body(OFFICIAL_DEEPSTATION, List.of(Map.of("name", "롱핀", "price", 9000, "sizeFormat", "SHOE_MM"))));

        mockMvc.perform(get("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.extensions").doesNotExist());
    }
}
