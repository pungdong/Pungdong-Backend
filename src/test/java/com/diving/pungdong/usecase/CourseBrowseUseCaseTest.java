package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.support.InstructorApprovalFixture;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * <p>그룹: S* 기본 둘러보기/종목, R* 지역 필터, F* 종류·레벨·단체·가격 필터, Q* 검색, N* 강사 축(닉네임),
 * O* 정렬,
 * P* 페이지 크기 상한·정렬 주입 방어, V* 비노출·빈 결과. 공개라 Authorization 헤더 없이 호출
 * (생성/공개만 강사 토큰).
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
    @Autowired com.diving.pungdong.account.ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired InstructorApplicationJpaRepo applicationRepo;

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
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll(); // account FK — 계정 삭제 후
    }

    /* ════════════════ seed 헬퍼 ════════════════ */

    private Account account(String email) {
        return account(email, email.split("@")[0]);
    }

    /** 닉네임을 직접 정하는 변형 — 강사명 검색(Q2) 처럼 닉네임이 검증 대상일 때. */
    private Account account(String email, String nickName) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
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
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
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
        // 발행(OPEN)은 그 종목의 정식 강사만 — 둘러보기에 뜨려면 승인이 있어야 한다.
        InstructorApprovalFixture.approve(applicationRepo, me, disciplineCode);
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
                .andExpect(jsonPath("$._embedded.courses[0].status").value("OPEN"))
                // 웹 sitemap 의 lastmod 가 이 둘로 나간다 — 둘 다 항상 채워져야 한다(BE #323).
                .andExpect(jsonPath("$._embedded.courses[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$._embedded.courses[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("S1b 카드에 강사 아바타가 실린다 — 사진을 안 올린 강사는 null(키는 있다)")
    void s1b_instructor_avatar() throws Exception {
        Account withPhoto = account("s1b-a@pungdong.com");
        withPhoto.setProfilePhoto(profilePhotoRepo.save(
                com.diving.pungdong.account.ProfilePhoto.builder().imageUrl("https://cdn/a.png").build()));
        accountRepo.save(withPhoto);
        Account noPhoto = account("s1b-b@pungdong.com");

        openCourse(withPhoto, trial("사진 있는 강사 강의"), "FREEDIVING", 90000,
                customRefAt(withPhoto, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(noPhoto, trial("사진 없는 강사 강의"), "FREEDIVING", 95000,
                customRefAt(noPhoto, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);

        browse("?disciplineCode=FREEDIVING&keyword=사진 있는")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].instructorAvatarUrl").value("https://cdn/a.png"));
        browse("?disciplineCode=FREEDIVING&keyword=사진 없는")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].instructorAvatarUrl").hasJsonPath())
                .andExpect(jsonPath("$._embedded.courses[0].instructorAvatarUrl").value(nullValue()));
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

    @Test
    @DisplayName("Q2 검색어가 강사 닉네임과 맞으면 제목에 없는 말이어도 그 강사 코스가 잡힌다")
    void q2_keyword_matches_instructor_nickname() throws Exception {
        Account minji = account("q2a@pungdong.com", "김민지");
        Account other = account("q2b@pungdong.com", "박지원");
        openCourse(minji, trial("입문 체험"), "FREEDIVING", 90000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(other, trial("주말 체험"), "FREEDIVING", 95000,
                customRefAt(other, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);

        browse("?disciplineCode=FREEDIVING&keyword=김민지")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("입문 체험"))
                .andExpect(jsonPath("$._embedded.courses[0].instructorName").value("김민지"));
    }

    @Test
    @DisplayName("Q3 검색어는 제목 OR 강사명 합집합 — 한쪽만 맞아도 둘 다 결과에 남는다")
    void q3_keyword_union_title_or_instructor() throws Exception {
        Account minji = account("q3a@pungdong.com", "김민지");
        Account other = account("q3b@pungdong.com", "박지원");
        // 제목으로 맞는 코스(강사는 다른 사람) + 강사명으로 맞는 코스(제목엔 그 말이 없음)
        openCourse(other, trial("김민지 강사님 추천 체험"), "FREEDIVING", 90000,
                customRefAt(other, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);
        openCourse(minji, trial("딥다이빙 트레이닝"), "FREEDIVING", 80000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(other, trial("무관한 체험"), "FREEDIVING", 95000,
                customRefAt(other, "문정 수영장", "서울특별시 송파구 법원로 128"), null);

        browse("?disciplineCode=FREEDIVING&keyword=김민지")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)))
                .andExpect(jsonPath("$._embedded.courses[*].title",
                        containsInAnyOrder("김민지 강사님 추천 체험", "딥다이빙 트레이닝")));
    }

    /* ════════════════ N — 강사 축(닉네임 정확 일치) ════════════════ */

    @Test
    @DisplayName("N1 강사 닉네임을 주면 그 강사의 강의만 남는다")
    void n1_instructor_nickname_filters() throws Exception {
        Account minji = account("n1a@pungdong.com", "김민지");
        Account other = account("n1b@pungdong.com", "박지원");
        openCourse(minji, trial("입문 체험"), "FREEDIVING", 90000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(minji, trial("주말 체험"), "FREEDIVING", 95000,
                customRefAt(minji, "문정 수영장", "서울특별시 송파구 법원로 128"), null);
        openCourse(other, trial("남의 체험"), "FREEDIVING", 99000,
                customRefAt(other, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);

        browse("?disciplineCode=FREEDIVING&instructorNickName=김민지")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)))
                .andExpect(jsonPath("$._embedded.courses[*].title",
                        containsInAnyOrder("입문 체험", "주말 체험")))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("N2 강사 축은 정확 일치다 — 닉네임이 앞부분만 같은 다른 강사는 안 섞인다")
    void n2_instructor_nickname_is_exact() throws Exception {
        Account minji = account("n2a@pungdong.com", "김민지");
        Account lookalike = account("n2b@pungdong.com", "김민지2");
        openCourse(minji, trial("본인 체험"), "FREEDIVING", 90000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(lookalike, trial("남의 체험"), "FREEDIVING", 95000,
                customRefAt(lookalike, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);

        // 검색어(keyword)로 같은 말을 치면 부분일치라 둘 다 잡힌다 — 두 축이 다르다는 게 요점.
        browse("?disciplineCode=FREEDIVING&keyword=김민지")
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)));

        browse("?disciplineCode=FREEDIVING&instructorNickName=김민지")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("본인 체험"));
    }

    @Test
    @DisplayName("N3 강사 축은 검색어와 AND 다 — 그 강사의 강의 중 제목이 맞는 것만 남는다")
    void n3_instructor_and_keyword_are_anded() throws Exception {
        Account minji = account("n3a@pungdong.com", "김민지");
        Account other = account("n3b@pungdong.com", "박지원");
        openCourse(minji, trial("딥다이빙 트레이닝"), "FREEDIVING", 80000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(minji, trial("입문 체험"), "FREEDIVING", 90000,
                customRefAt(minji, "문정 수영장", "서울특별시 송파구 법원로 128"), null);
        openCourse(other, trial("딥다이빙 특강"), "FREEDIVING", 99000,
                customRefAt(other, "올림픽수영장", "서울특별시 송파구 올림픽로 424"), null);

        browse("?disciplineCode=FREEDIVING&instructorNickName=김민지&keyword=딥다이빙")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("딥다이빙 트레이닝"));
    }

    @Test
    @DisplayName("N4 없는 닉네임은 400 이 아니라 빈 페이지다 (종목 코드와 같은 규칙)")
    void n4_unknown_nickname_is_empty_page() throws Exception {
        Account me = account("n4@pungdong.com", "김민지");
        openCourse(me, trial("입문 체험"), "FREEDIVING", 90000,
                customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);

        browse("?disciplineCode=FREEDIVING&instructorNickName=없는강사")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("N5 강사 축은 다른 필터를 무력화하지 않는다 — 그 강사 강의라도 지역이 다르면 빠진다")
    void n5_instructor_composes_with_other_filters() throws Exception {
        Account minji = account("n5@pungdong.com", "김민지");
        openCourse(minji, trial("서울 체험"), "FREEDIVING", 90000,
                customRefAt(minji, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);
        openCourse(minji, trial("제주 체험"), "FREEDIVING", 95000,
                customRefAt(minji, "제주 딥풀", "제주특별자치도 서귀포시 중문관광로 72"), null);

        browse("?disciplineCode=FREEDIVING&instructorNickName=김민지&region=JEJU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("제주 체험"));
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

    /* ════════════════ P — 페이지 크기 상한 · 정렬 주입 방어 ════════════════ */

    @Test
    @DisplayName("P1 과대한 size(100000)를 보내도 한 페이지는 50개까지다 — 카탈로그 전수 스크래핑 차단")
    void p1_size_is_clamped() throws Exception {
        Account me = account("p1@pungdong.com");
        openCourse(me, trial("체험"), "FREEDIVING", 90000,
                customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25"), null);

        browse("?disciplineCode=FREEDIVING&size=100000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    @Test
    @DisplayName("P2 size 를 안 보내면 한 페이지는 20개다")
    void p2_default_size() throws Exception {
        // 이 20 을 만드는 건 PageClamp.DEFAULT_PAGE_SIZE 가 아니라 Spring 리졸버의 fallback 이다
        // (리졸버는 unpaged 를 주지 않아 PageClamp 의 기본값 가지가 실행되지 않는다). FE 계약으로서의
        // "기본 20" 을 고정하는 테스트이지, clamp 구현을 검증하는 테스트가 아니다.
        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @DisplayName("P3 정렬 창구는 화이트리스트 enum 하나뿐 — Pageable 형식(sort=price,asc)은 400 이지 임의 정렬이 아니다")
    void p3_client_sort_is_not_a_backdoor() throws Exception {
        // 이 방어는 clamp 가 아니라 컨트롤러의 enum 파라미터가 한다(이 PR 이전에도 같았다).
        // 계약서가 "무시된다" 가 아니라 "400 이다" 라고 적어야 하는 근거를 고정해두는 회귀 가드.
        Account me = account("p3@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, certification("비싼 과정", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 350000, ref, null);
        openCourse(me, trial("싼 체험"), "FREEDIVING", 90000, ref, null);

        browse("?disciplineCode=FREEDIVING&sort=price,asc")
                .andExpect(status().isBadRequest());

        // 화이트리스트 값은 정상 동작(임의 컬럼이 아니라 이 enum 만이 정렬 창구다)
        browse("?disciplineCode=FREEDIVING&sort=PRICE_ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].title").value("싼 체험"));
    }

    @Test
    @DisplayName("P4 서버가 만든 HAL 링크(self·next)를 그대로 따라가도 200 이다 — 링크에 내부 정렬이 새지 않는다")
    void p4_hal_links_are_followable() throws Exception {
        Account me = account("p4@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        openCourse(me, trial("체험1"), "FREEDIVING", 90000, ref, null);
        openCourse(me, trial("체험2"), "FREEDIVING", 95000, ref, null);

        String body = browse("?disciplineCode=FREEDIVING&size=1")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // self 는 물론 next 도 실제로 호출 가능해야 한다. 예전엔 assembler 가 서버 내부 정렬을
        // sort=createdAt,id,desc 로 링크에 실었고, 그 값이 enum 파라미터로 되돌아와 400 이 났다.
        for (String rel : List.of("self", "next")) {
            String href = JsonPath.read(body, "$._links." + rel + ".href");
            org.assertj.core.api.Assertions.assertThat(href).doesNotContain("createdAt");
            mockMvc.perform(get(java.net.URI.create(href)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("P5 정렬을 걸고 next 링크를 따라가면 그 정렬이 유지된다 — 안 그러면 같은 강의가 두 번 나온다")
    void p5_next_link_keeps_sort() throws Exception {
        Account me = account("p5@pungdong.com");
        String ref = customRefAt(me, "잠실 잠수풀", "서울특별시 송파구 올림픽로 25");
        // 가격 오름차순과 최신순(기본)이 정확히 반대가 되도록 비싼 것부터 만든다
        openCourse(me, certification("300", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 300000, ref, null);
        openCourse(me, certification("200", "AIDA", List.of("LEVEL_2")), "FREEDIVING", 200000, ref, null);
        openCourse(me, trial("100"), "FREEDIVING", 100000, ref, null);

        String first = browse("?disciplineCode=FREEDIVING&sort=PRICE_ASC&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].title").value("100"))
                .andReturn().getResponse().getContentAsString();

        // assembler 는 요청의 sort 를 지우고 Page 의 Sort 로 대체한다. 우리는 Sort 를 버렸으므로
        // 손대지 않으면 여기서 sort 가 통째로 사라지고, next 가 '최신순 2페이지'(= "200")를 준다.
        String next = JsonPath.read(first, "$._links.next.href");
        org.assertj.core.api.Assertions.assertThat(next).contains("sort=PRICE_ASC");
        mockMvc.perform(get(java.net.URI.create(next)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].title").value("200"));
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
