package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.favorite.VenueFavoriteJpaRepo;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사별 위치 즐겨찾기 use-case — 코스빌더 picker 의 "내 위치" 묶음을 서버에 영속한다.
 * 실 H2 + 임베디드 Redis + 시큐리티 체인, Sanity 만 {@link com.diving.pungdong.venue.sync.StubSanityVenueClient}.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 읽으면 그대로 스펙이다.
 * S* = 성공 경로, D* = 멱등(중복/없는 것), V* = 입력 검증, R* = 권한·격리, L* = 수명주기.
 *
 * <p>⚠️ {@code Authorization} 헤더는 <b>raw JWT</b>(Bearer prefix 없음 — {@code JwtTokenProvider.resolveToken}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VenueFavoriteUseCaseTest {

    private static final String DEEPSTATION_REF = "OFFICIAL:official-deepstation";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired VenueFavoriteJpaRepo favoriteRepo;
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
        favoriteRepo.deleteAll();
        venueRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── 픽스처 ─────────────────────────────────────────────── */

    private Account account(String email) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(email.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private Venue seedCustom(Account owner, String name) {
        return venueRepo.save(Venue.builder()
                .owner(owner).name(name).type(VenueType.OCEAN).lockedDisciplineCode("FREEDIVING")
                .address("강원특별자치도 양양군 현남면").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private String customRef(Venue v) {
        return "CUSTOM:" + v.getId();
    }

    private String body(String venueRefId) {
        return "{\"venueRefId\":\"" + venueRefId + "\"}";
    }

    /* ─── S — 성공 경로 ───────────────────────────────────────── */

    @Test
    @DisplayName("S1 내 커스텀 위치를 즐겨찾기하면 저장되고, 빌더 목록에 favorite=true 로 함께 온다")
    void s1_mark_custom_shows_in_builder() throws Exception {
        Account me = account("s1@pungdong.com");
        Venue mine = seedCustom(me, "내 죽도 포인트");

        mockMvc.perform(post("/venue-favorites")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customRef(mine))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueRefId").value(customRef(mine)));

        Assertions.assertThat(favoriteRepo.count()).isEqualTo(1);

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='내 죽도 포인트')].favorite").value(hasItem(true)));
    }

    @Test
    @DisplayName("S2 공식(OFFICIAL) 위치도 즐겨찾기된다 — 소유 개념이 없어도 Sanity 카탈로그에 있으면 허용")
    void s2_mark_official() throws Exception {
        Account me = account("s2@pungdong.com");

        mockMvc.perform(post("/venue-favorites")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].favorite").value(hasItem(true)))
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='양양 비치 포인트')].favorite").value(hasItem(false)));
    }

    @Test
    @DisplayName("S3 해제하면 빌더 목록이 favorite=false 로 돌아온다")
    void s3_unmark_reverts_builder() throws Exception {
        Account me = account("s3@pungdong.com");
        mockMvc.perform(post("/venue-favorites").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)));

        mockMvc.perform(delete("/venue-favorites").param("venueRefId", DEEPSTATION_REF)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        Assertions.assertThat(favoriteRepo.count()).isZero();

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].favorite").value(hasItem(false)));
    }

    @Test
    @DisplayName("S4 내 즐겨찾기 목록(GET)은 마크한 위치 토큰을 돌려준다")
    void s4_list_mine() throws Exception {
        Account me = account("s4@pungdong.com");
        mockMvc.perform(post("/venue-favorites").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)));

        mockMvc.perform(get("/venue-favorites").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venueFavorites[*].venueRefId").value(hasItem(DEEPSTATION_REF)));
    }

    /* ─── D — 멱등 ───────────────────────────────────────────── */

    @Test
    @DisplayName("D1 같은 위치를 두 번 마크해도 200 이고 표식은 1개다(멱등) — 재마크는 최초 createdAt 을 보존한다")
    void d1_mark_is_idempotent() throws Exception {
        Account me = account("d1@pungdong.com");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/venue-favorites")
                            .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                            .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.venueRefId").value(DEEPSTATION_REF))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        Assertions.assertThat(favoriteRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("D2 즐겨찾기하지 않은 위치를 해제해도 204 다(멱등) — 에러가 아니다")
    void d2_unmark_is_idempotent() throws Exception {
        Account me = account("d2@pungdong.com");

        mockMvc.perform(delete("/venue-favorites").param("venueRefId", DEEPSTATION_REF)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        Assertions.assertThat(favoriteRepo.count()).isZero();
    }

    /* ─── V — 입력 검증 ──────────────────────────────────────── */

    @Test
    @DisplayName("V1 venueRefId 형식이 어긋나면(\"12\") 400 이고 아무것도 저장되지 않는다")
    void v1_malformed_ref_rejected() throws Exception {
        Account me = account("v1@pungdong.com");

        mockMvc.perform(post("/venue-favorites")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body("12")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("위치 식별자 형식이 올바르지 않습니다."));

        Assertions.assertThat(favoriteRepo.count()).isZero();
    }

    @Test
    @DisplayName("V2 존재하지 않는 공식 위치는 즐겨찾기할 수 없다 — 400")
    void v2_unknown_official_rejected() throws Exception {
        Account me = account("v2@pungdong.com");

        mockMvc.perform(post("/venue-favorites")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body("OFFICIAL:없는위치")))
                .andExpect(status().isBadRequest());

        Assertions.assertThat(favoriteRepo.count()).isZero();
    }

    /* ─── R — 권한·격리 ─────────────────────────────────────── */

    @Test
    @DisplayName("R1 남의 커스텀 위치는 즐겨찾기할 수 없다 — 400(존재를 떠보는 통로를 막는다)")
    void r1_other_custom_rejected() throws Exception {
        Account me = account("r1f@pungdong.com");
        Account other = account("r1f-other@pungdong.com");
        Venue theirs = seedCustom(other, "남의 포인트");

        mockMvc.perform(post("/venue-favorites")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customRef(theirs))))
                .andExpect(status().isBadRequest());

        Assertions.assertThat(favoriteRepo.count()).isZero();
    }

    @Test
    @DisplayName("R2 내 즐겨찾기는 다른 강사의 빌더 목록에 새지 않는다")
    void r2_favorite_is_per_instructor() throws Exception {
        Account me = account("r2f@pungdong.com");
        Account other = account("r2f-other@pungdong.com");
        mockMvc.perform(post("/venue-favorites").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)));

        mockMvc.perform(get("/venues/builder").header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[?(@.name=='딥스테이션')].favorite").value(hasItem(false)));
    }

    @Test
    @DisplayName("R3 비로그인 요청은 401 이다")
    void r3_anonymous_rejected() throws Exception {
        mockMvc.perform(post("/venue-favorites")
                        .contentType(MediaType.APPLICATION_JSON).content(body(DEEPSTATION_REF)))
                .andExpect(status().isUnauthorized());
    }

    /* ─── L — 수명주기 ──────────────────────────────────────── */

    @Test
    @DisplayName("L1 커스텀 위치를 삭제하면 그 위치를 가리키던 즐겨찾기 표식도 함께 사라진다")
    void l1_venue_delete_cleans_favorite() throws Exception {
        Account me = account("l1f@pungdong.com");
        Venue mine = seedCustom(me, "지울 포인트");
        mockMvc.perform(post("/venue-favorites").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON).content(body(customRef(mine))));
        Assertions.assertThat(favoriteRepo.count()).isEqualTo(1);

        mockMvc.perform(delete("/venues/" + mine.getId()).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        Assertions.assertThat(favoriteRepo.count()).isZero();
    }
}
