package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 빌더 위치 통합 목록(GET /venues/builder) use-case — OFFICIAL(Sanity, {@link
 * com.diving.pungdong.venue.sync.StubSanityVenueClient} 고정 2건)+CUSTOM(내 DB)을 BE 가 합쳐 돌려준다.
 * 실 H2 + 임베디드 Redis + 시큐리티 체인, Sanity 만 stub(실 외부 미접속).
 *
 * <p><b>읽는 법</b>: B* = 빌더 머지/매핑/필터, R* = 소유 격리. 스텁 official:
 * 딥스테이션(DEEP_POOL · FREEDIVING·SCUBA), 양양(OCEAN · SCUBA).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VenueBuilderUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;

    /** 공식 위치 캐시(임베디드 Redis, process-전역)를 매 테스트 비워 stub 에서 새로 lazy-load. */
    @BeforeEach
    void flushOfficialCache() {
        Set<String> keys = redisTemplate.keys("venue:official:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void cleanUp() {
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

    /** 소유자에게 종속된 커스텀 위치 1건 직접 시드(생성 게이트/검증은 VenueUseCaseTest 담당). */
    private Venue seedCustom(Account owner, String name, VenueType type, String disciplineCode) {
        return venueRepo.save(Venue.builder()
                .owner(owner).name(name).type(type).lockedDisciplineCode(disciplineCode)
                .createdAt(LocalDateTime.now()).build());
    }

    /* ════════════════ B — 빌더 머지/매핑/필터 ════════════════ */

    @Test
    @DisplayName("B1 필터 없이 요청하면 OFFICIAL 2건이 통합 목록에 매핑되어 온다(수심·이용시간 파생 포함)")
    void b1_official_mapped() throws Exception {
        Account me = account("b1@pungdong.com");

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.scope=='OFFICIAL')]").value(hasSize(2)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].venueRefId")
                        .value(hasItem("OFFICIAL:official-deepstation")))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].maxDepth").value(hasItem(36)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].type").value(hasItem("DEEP_POOL")));
    }

    @Test
    @DisplayName("B2 disciplineCode=FREEDIVING 이면 SCUBA 전용 OFFICIAL(양양)은 빠진다")
    void b2_discipline_filter() throws Exception {
        Account me = account("b2@pungdong.com");

        mockMvc.perform(get("/venues/builder").param("disciplineCode", "FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')]").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='양양 비치 포인트')]").value(empty()));
    }

    @Test
    @DisplayName("B3 disciplineCode=SCUBA 면 두 OFFICIAL 모두 포함된다")
    void b3_scuba_includes_both() throws Exception {
        Account me = account("b3@pungdong.com");

        mockMvc.perform(get("/venues/builder").param("disciplineCode", "SCUBA")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.scope=='OFFICIAL')]").value(hasSize(2)));
    }

    @Test
    @DisplayName("B4 내 CUSTOM 위치가 OFFICIAL 과 함께 통합 목록에 합쳐진다")
    void b4_custom_merged() throws Exception {
        Account me = account("b4@pungdong.com");
        seedCustom(me, "내 죽도 포인트", VenueType.OCEAN, "FREEDIVING");

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.scope=='OFFICIAL')]").value(hasSize(2)))
                .andExpect(jsonPath("$._embedded.venues[?(@.scope=='CUSTOM')]").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='내 죽도 포인트')].venueRefId")
                        .value(hasItem(startsWith("CUSTOM:"))));
    }

    @Test
    @DisplayName("B5 type=OCEAN 이면 OFFICIAL 중 해양(양양)만 남는다")
    void b5_type_filter() throws Exception {
        Account me = account("b5@pungdong.com");

        mockMvc.perform(get("/venues/builder").param("type", "OCEAN")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='양양 비치 포인트')]").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')]").value(empty()));
    }

    /* ════════════════ F — 이용권 종목 필터 · ticketRef ════════════════ */

    @Test
    @DisplayName("F1 FREEDIVING 빌더 — 딥스테이션의 프리 이용권만, 스쿠버 전용 일반권은 빠진다(ticket 종목 필터)")
    void f1_ticket_filtered_by_discipline() throws Exception {
        Account me = account("f1@pungdong.com");

        mockMvc.perform(get("/venues/builder").param("disciplineCode", "FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].tickets[*].name", hasItem("일반권")))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].tickets[*].name", not(hasItem("스쿠버 일반권"))));
    }

    @Test
    @DisplayName("F2 SCUBA 빌더 — 딥스테이션의 프리·스쿠버 이용권 모두, 각 ticket 에 안정 ticketRef(=Sanity _key)")
    void f2_scuba_tickets_and_ticketref() throws Exception {
        Account me = account("f2@pungdong.com");

        mockMvc.perform(get("/venues/builder").param("disciplineCode", "SCUBA")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].tickets[*].name", hasItems("일반권", "스쿠버 일반권")))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].tickets[*].ticketRef", hasItems("deep-tk-1", "deep-tk-2")));
    }

    /* ════════════════ R — 소유 격리 ════════════════ */

    @Test
    @DisplayName("R1 남의 CUSTOM 위치는 내 통합 목록에 안 보인다(OFFICIAL 만)")
    void r1_other_custom_hidden() throws Exception {
        Account me = account("r1@pungdong.com");
        Account other = account("r1-other@pungdong.com");
        seedCustom(other, "남의 포인트", VenueType.OCEAN, "FREEDIVING");

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.scope=='CUSTOM')]").value(empty()))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='남의 포인트')]").value(empty()));
    }
}
