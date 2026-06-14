package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 둘러보기(GET /courses/browse) use-case = 실행 가능한 사양. 수강생 메인 홈/필터 시트가 호출하는
 * 공개 조회. 실 H2 + 시큐리티, 위치는 주소를 박은 CUSTOM 으로 seed 해 지역 파생을 검증한다.
 * {@code @DisplayName} 을 위→아래로 읽으면 둘러보기 규칙이 된다.
 *
 * <p>그룹: S* 기본 둘러보기/종목, R* 지역 필터, F* 종류·레벨·단체·가격 필터, Q* 검색, O* 정렬,
 * V* 비노출·빈 결과. 공개라 Authorization 헤더 없이 호출(생성/공개만 강사 토큰).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseBrowseUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void flushOfficialCache() {
        Set<String> keys = redisTemplate.keys("venue:official:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void cleanUp() {
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ════════════════ seed 헬퍼 ════════════════ */

    private Account account(String email) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(email.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    /** 주소를 박은 CUSTOM 위치 — 지역 파생(주소→시·도→묶음) 검증용. */
    private String customRefAt(Account owner, String name, String address) {
        Venue v = venueRepo.save(Venue.builder()
                .owner(owner).name(name).type(VenueType.SWIMMING_POOL)
                .address(address).lockedDisciplineCode("FREEDIVING")
                .createdAt(LocalDateTime.now()).build());
        return "CUSTOM:" + v.getId();
    }

    private Map<String, Object> round(String venueRefId) {
        return Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", venueRefId,
                        "tickets", List.of(Map.of("ticketRef", "ticket-1", "daypart", "WEEKDAY")))));
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    /** 코스 작성(POST) → OPEN 전이까지. 둘러보기는 OPEN 만 노출하므로 seed 의 기본 단위. */
    private long openCourse(Account me, Map<String, Object> typeFields, String disciplineCode,
                            int price, String venueRef, String mediaUrl) throws Exception {
        Map<String, Object> body = new HashMap<>(typeFields);
        body.put("disciplineCode", disciplineCode);
        body.put("price", price);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(round(venueRef)));
        if (mediaUrl != null) {
            body.put("media", List.of(Map.of("kind", "PHOTO", "url", mediaUrl)));
        }
        String location = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(location, "$.id")).longValue();
        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isOk());
        return id;
    }

    /** 체험(TRIAL) 코스 타입 필드. */
    private Map<String, Object> trial(String title) {
        return Map.of("title", title, "kind", "TRIAL");
    }

    /** 자격(CERTIFICATION) 코스 타입 필드. */
    private Map<String, Object> certification(String title, String org, List<String> levels) {
        return Map.of("title", title, "kind", "CERTIFICATION", "organizationCode", org, "levels", levels);
    }

    private ResultActions browse(String query) throws Exception {
        return mockMvc.perform(get("/courses/browse" + query));
    }

    /* ════════════════ S — 기본 둘러보기 · 종목 ════════════════ */

    @Test
    @DisplayName("S1 비로그인으로 둘러보면 OPEN 코스가 카드 필드(제목·강사·위치·지역·가격·썸네일)와 함께 온다")
    void s1_browse_cards() throws Exception {
        Account me = account("s1@pungdong.com");
        openCourse(me, certification("AIDA2 프리다이빙 과정", "AIDA", List.of("LEVEL_2")),
                "FREEDIVING", 350000, customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), "http://img/cover.jpg");

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$._embedded.courses[0].instructorName").value("s1"))
                .andExpect(jsonPath("$._embedded.courses[0].locationName").value("잠실 잠수풀"))
                .andExpect(jsonPath("$._embedded.courses[0].regions", hasItem("SEOUL_GYEONGGI")))
                .andExpect(jsonPath("$._embedded.courses[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$._embedded.courses[0].thumbnailUrl").value("http://img/cover.jpg"))
                .andExpect(jsonPath("$._embedded.courses[0].price").value(350000))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("S2 종목으로 좁힌다 — 프리다이빙만 요청하면 스쿠버 코스는 빠진다")
    void s2_filter_by_discipline() throws Exception {
        Account me = account("s2@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("프리 체험"), "FREEDIVING", 90000, ref, null);
        openCourse(me, trial("스쿠버 체험"), "SCUBA", 120000, ref, null);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("프리 체험"));
    }

    /* ════════════════ R — 지역 필터 (주소 파생) ════════════════ */

    @Test
    @DisplayName("R1 지역=서울·경기 면 경기도 용인 위치 코스만 오고 부산 코스는 빠진다")
    void r1_region_filter() throws Exception {
        Account me = account("r1@pungdong.com");
        openCourse(me, trial("용인 체험"), "FREEDIVING", 90000,
                customRefAt(me, "용인 수영장", "경기도 용인시 처인구 중부대로 1"), null);
        openCourse(me, trial("부산 체험"), "FREEDIVING", 90000,
                customRefAt(me, "해운대 풀", "부산광역시 해운대구 우동 1"), null);

        browse("?disciplineCode=FREEDIVING&region=SEOUL_GYEONGGI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("용인 체험"));
    }

    @Test
    @DisplayName("R2 묶이지 않는 지역(충청)은 ETC 라 명시 지역 필터엔 안 뜨지만 전체엔 포함된다")
    void r2_etc_only_in_all() throws Exception {
        Account me = account("r2@pungdong.com");
        openCourse(me, trial("대전 체험"), "FREEDIVING", 90000,
                customRefAt(me, "대전 수영장", "대전광역시 유성구 대학로 99"), null);

        browse("?disciplineCode=FREEDIVING&region=SEOUL_GYEONGGI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].regions", hasItem("ETC")));
    }

    /* ════════════════ F — 종류 · 레벨 · 단체 · 가격 ════════════════ */

    @Test
    @DisplayName("F1 평탄 칩 — 종류(체험)만 고르면 TRIAL 만, 레벨(L1)만 고르면 그 레벨 자격 과정만")
    void f1_flat_kind_or_level() throws Exception {
        Account me = account("f1@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("입문 체험"), "FREEDIVING", 90000, ref, null);
        openCourse(me, certification("AIDA1 과정", "AIDA", List.of("LEVEL_1")), "FREEDIVING", 320000, ref, null);

        browse("?disciplineCode=FREEDIVING&kinds=TRIAL")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("입문 체험"));
        browse("?disciplineCode=FREEDIVING&levels=LEVEL_1")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("AIDA1 과정"));
    }

    @Test
    @DisplayName("F1b 평탄 칩 멀티선택은 OR 합집합 — 체험 칩 + L1 칩 = 체험 코스와 L1 자격 과정 둘 다, L2는 빠짐")
    void f1b_flat_chips_union() throws Exception {
        Account me = account("f1b@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("입문 체험"), "FREEDIVING", 90000, ref, null);
        openCourse(me, certification("AIDA1 과정", "AIDA", List.of("LEVEL_1")), "FREEDIVING", 320000, ref, null);
        openCourse(me, certification("AIDA2 과정", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 350000, ref, null);

        browse("?disciplineCode=FREEDIVING&kinds=TRIAL&levels=LEVEL_1")
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)))
                .andExpect(jsonPath("$._embedded.courses[*].title",
                        containsInAnyOrder("입문 체험", "AIDA1 과정")));
    }

    @Test
    @DisplayName("F2 단체=AIDA 면 PADI 자격 과정은 빠진다")
    void f2_organization() throws Exception {
        Account me = account("f2@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, certification("AIDA2 과정", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 350000, ref, null);
        openCourse(me, certification("PADI 과정", "PADI", List.of("LEVEL_2")), "FREEDIVING", 420000, ref, null);

        browse("?disciplineCode=FREEDIVING&organizationCodes=AIDA")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("AIDA2 과정"));
    }

    @Test
    @DisplayName("F3 가격 밴드(minPrice) 로 10만원 이하/이상이 갈린다")
    void f3_price_band() throws Exception {
        Account me = account("f3@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("싼 체험"), "FREEDIVING", 90000, ref, null);
        openCourse(me, certification("비싼 과정", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 350000, ref, null);

        browse("?disciplineCode=FREEDIVING&minPrice=100000")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("비싼 과정"));
        browse("?disciplineCode=FREEDIVING&maxPrice=100000")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("싼 체험"));
    }

    /* ════════════════ Q — 검색 ════════════════ */

    @Test
    @DisplayName("Q1 검색어로 제목을 부분 일치로 찾는다")
    void q1_keyword() throws Exception {
        Account me = account("q1@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("딥다이빙 트레이닝"), "FREEDIVING", 80000, ref, null);
        openCourse(me, trial("입문 체험"), "FREEDIVING", 90000, ref, null);

        browse("?disciplineCode=FREEDIVING&keyword=딥다이빙")
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("딥다이빙 트레이닝"));
    }

    /* ════════════════ O — 정렬 ════════════════ */

    @Test
    @DisplayName("O1 정렬=가격오름차순 이면 싼 코스가 먼저 온다")
    void o1_sort_price_asc() throws Exception {
        Account me = account("o1@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, certification("비싼 과정", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 350000, ref, null);
        openCourse(me, trial("싼 체험"), "FREEDIVING", 90000, ref, null);

        browse("?disciplineCode=FREEDIVING&sort=PRICE_ASC")
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("싼 체험"))
                .andExpect(jsonPath("$._embedded.courses[1].title").value("비싼 과정"));
    }

    /* ════════════════ V — 비노출 · 빈 결과 ════════════════ */

    @Test
    @DisplayName("V1 DRAFT(안 연) 코스는 둘러보기에 안 뜬다 — OPEN 만 공개")
    void v1_draft_hidden() throws Exception {
        Account me = account("v1@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("공개 체험"), "FREEDIVING", 90000, ref, null);
        // DRAFT 로만 둔 코스 — OPEN 전이 안 함
        Map<String, Object> draft = new HashMap<>(trial("임시 체험"));
        draft.put("disciplineCode", "FREEDIVING");
        draft.put("price", 90000);
        draft.put("totalRounds", 1);
        draft.put("rounds", List.of(round(ref)));
        mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(draft)))
                .andExpect(status().isCreated());

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("공개 체험"));
    }

    @Test
    @DisplayName("V2 결과가 없으면 에러가 아니라 200 빈 페이지(totalElements 0)")
    void v2_empty_is_ok() throws Exception {
        browse("?disciplineCode=MERMAID")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("V3 종목(disciplineCode) 없이 부르면 400 — 종목은 필수")
    void v3_discipline_required() throws Exception {
        browse("")
                .andExpect(status().isBadRequest());
    }
}
