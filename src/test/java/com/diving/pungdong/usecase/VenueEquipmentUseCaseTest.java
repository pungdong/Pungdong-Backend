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
 * 대여 장비 가격표(venue-extension) use-case — 강사×위치 가격표 upsert/조회 + 위치 참조 검증 + 사이즈 형식.
 * 실 H2 + 임베디드 Redis + 시큐리티, Sanity 는 stub(official id = official-deepstation).
 *
 * <p>E* = 저장/조회/사이즈, V* = 위치 참조 검증 거절, R* = 소유 격리.
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
                .createdAt(LocalDateTime.now()).build());
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
