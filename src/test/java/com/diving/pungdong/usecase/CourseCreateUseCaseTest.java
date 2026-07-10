package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.equipment.VenueEquipmentExtensionJpaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 작성(course-create) use-case = 실행 가능한 사양. 실 H2 + 임베디드 Redis + 시큐리티, Sanity stub.
 * {@code @DisplayName} 을 위→아래로 읽으면 코스 생성 규칙이 된다.
 *
 * <p>그룹: S* 성공/생성, P* 패키지·추가세션, E* 장비 합성, V* 검증 거절, R* 권한·격리, T* 상태전이.
 * 위치 참조 = venueRefId("CUSTOM:&lt;pk&gt;"/"OFFICIAL:official-deepstation").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseCreateUseCaseTest {

    private static final String OFFICIAL_DEEPSTATION = "OFFICIAL:official-deepstation";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired VenueEquipmentExtensionJpaRepo extensionRepo;
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

    private String customRef(Account owner) {
        Venue v = venueRepo.save(Venue.builder()
                .owner(owner).name("내 죽도 포인트").type(VenueType.OCEAN).lockedDisciplineCode("FREEDIVING")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return "CUSTOM:" + v.getId();
    }

    private Map<String, Object> venue(String venueRefId) {
        return Map.of("venueRefId", venueRefId,
                "tickets", List.of(Map.of("ticketRef", "ticket-1", "daypart", "WEEKDAY")));
    }

    private Map<String, Object> round(String desc, String venueRefId) {
        return Map.of("description", desc, "venues", List.of(venue(venueRefId)));
    }

    private String body(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    private ResultActions createCourse(Account me, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON).content(body(body)));
    }

    /* ════════════════ S — 생성 ════════════════ */

    @Test
    @DisplayName("S1 자격 과정(단체+레벨) 코스를 만들면 201·DRAFT 로 저장되고 회차/위치가 박힌다")
    void s1_create_certification() throws Exception {
        Account me = account("s1@pungdong.com");
        Map<String, Object> body = Map.of(
                "title", "AIDA2 프리다이빙 과정", "kind", "CERTIFICATION",
                "organizationCode", "AIDA", "disciplineCode", "FREEDIVING",
                "levels", List.of("LEVEL_2"), "totalRounds", 1, "price", 350000,
                "description", "자유잠수 기초",
                "rounds", List.of(round("1회차 적응", customRef(me))));

        createCourse(me, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.kind").value("CERTIFICATION"))
                .andExpect(jsonPath("$.isPackage").value(false))
                .andExpect(jsonPath("$.rounds[0].roundIndex").value(1))
                .andExpect(jsonPath("$.rounds[0].platformConfirmed").value(true))
                .andExpect(jsonPath("$.rounds[0].venues[0].tickets[0].daypart").value("WEEKDAY"));

        org.assertj.core.api.Assertions.assertThat(courseRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2 공식(OFFICIAL) 위치를 회차에 쓸 수 있다")
    void s2_official_venue() throws Exception {
        Account me = account("s2@pungdong.com");
        Map<String, Object> body = Map.of(
                "title", "딥스테이션 과정", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 90000,
                "rounds", List.of(round("체험", OFFICIAL_DEEPSTATION)));

        createCourse(me, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds[0].venues[0].venueRefId").value(OFFICIAL_DEEPSTATION));
    }

    /* ════════════════ P — 패키지 · 추가세션 ════════════════ */

    @Test
    @DisplayName("P1 레벨 2개를 고르면 자동으로 패키지(isPackage=true)다")
    void p1_package() throws Exception {
        Account me = account("p1@pungdong.com");
        Map<String, Object> body = Map.of(
                "title", "AIDA1+2 패키지", "kind", "CERTIFICATION", "organizationCode", "AIDA",
                "disciplineCode", "FREEDIVING", "levels", List.of("LEVEL_1", "LEVEL_2"),
                "totalRounds", 1, "price", 580000,
                "rounds", List.of(round("1회차", customRef(me))));

        createCourse(me, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPackage").value(true))
                .andExpect(jsonPath("$.levels", containsInAnyOrder("LEVEL_1", "LEVEL_2")));
    }

    @Test
    @DisplayName("P2 추가세션을 주면 EXTRA 회차로 비용 정책(무료 N회·이후 회당가)과 함께 저장된다")
    void p2_extra_session() throws Exception {
        Account me = account("p2@pungdong.com");
        String ref = customRef(me);
        Map<String, Object> body = Map.of(
                "title", "보충 포함 과정", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 300000,
                "rounds", List.of(round("1회차", ref)),
                "extraSession", Map.of("description", "보충 세션", "freeCount", 1, "perSessionPrice", 50000,
                        "venues", List.of(venue(ref))));

        createCourse(me, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds[?(@.roundKind=='EXTRA')].freeCount").value(hasItem(1)))
                .andExpect(jsonPath("$.rounds[?(@.roundKind=='EXTRA')].perSessionPrice").value(hasItem(50000)));
    }

    /* ════════════════ E — 위치별 장비 합성 ════════════════ */

    @Test
    @DisplayName("E1 코스 상세에서 위치별 대여 장비가 강사×위치 가격표로부터 합성돼 온다")
    void e1_equipment_synthesis() throws Exception {
        Account me = account("e1@pungdong.com");
        String ref = customRef(me);
        // 그 위치의 장비 가격표 먼저 등록
        mockMvc.perform(put("/venue-equipment").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("venueRefId", ref, "items",
                                List.of(Map.of("name", "롱핀", "price", 5000, "sizeFormat", "SHOE_MM"))))))
                .andExpect(status().isOk());

        String location = createCourse(me, Map.of(
                "title", "장비 과정", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 100000, "rounds", List.of(round("1회차", ref))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(location, "$.id")).longValue();

        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds[0].venues[0].equipment.items[0].name").value("롱핀"))
                .andExpect(jsonPath("$.rounds[0].venues[0].equipment.items[0].sizeOptions").value(hasItem("250")));
    }

    /* ════════════════ V — 검증 거절 ════════════════ */

    @Test
    @DisplayName("V1 자격 과정인데 레벨이 없으면 400")
    void v1_cert_without_levels() throws Exception {
        Account me = account("v1@pungdong.com");
        createCourse(me, Map.of("title", "x", "kind", "CERTIFICATION", "organizationCode", "AIDA",
                "disciplineCode", "FREEDIVING", "levels", List.of(), "totalRounds", 1, "price", 1000,
                "rounds", List.of(round("1", customRef(me)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 회차 개수가 totalRounds 와 다르면 400")
    void v2_round_count_mismatch() throws Exception {
        Account me = account("v2@pungdong.com");
        createCourse(me, Map.of("title", "x", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 3, "price", 1000, "rounds", List.of(round("1", customRef(me)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V3 없는 종목 코드면 400")
    void v3_bad_discipline() throws Exception {
        Account me = account("v3@pungdong.com");
        createCourse(me, Map.of("title", "x", "kind", "TRIAL", "disciplineCode", "NOPE",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", customRef(me)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V4 남의 커스텀 위치를 회차에 쓰면 400")
    void v4_others_custom_venue() throws Exception {
        Account me = account("v4@pungdong.com");
        Account other = account("v4-other@pungdong.com");
        String othersRef = customRef(other);
        createCourse(me, Map.of("title", "x", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", othersRef))))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ R · T — 권한 · 상태 ════════════════ */

    @Test
    @DisplayName("R1 남의 코스 상세는 못 본다(400, 존재 숨김)")
    void r1_others_course_hidden() throws Exception {
        Account owner = account("r1-owner@pungdong.com");
        String location = createCourse(owner, Map.of("title", "남 코스", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", customRef(owner)))))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(location, "$.id")).longValue();

        Account intruder = account("r1-intruder@pungdong.com");
        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(intruder)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T1 상태를 OPEN 으로 전이할 수 있다(검수 없음)")
    void t1_status_open() throws Exception {
        Account me = account("t1@pungdong.com");
        String location = createCourse(me, Map.of("title", "x", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", customRef(me)))))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(location, "$.id")).longValue();

        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "OPEN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("L1 내 강의 목록엔 내 코스만 보인다")
    void l1_list_mine() throws Exception {
        Account me = account("l1@pungdong.com");
        Account other = account("l1-other@pungdong.com");
        createCourse(me, Map.of("title", "내 코스", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", customRef(me))))).andExpect(status().isCreated());
        createCourse(other, Map.of("title", "남 코스", "kind", "TRIAL", "disciplineCode", "FREEDIVING",
                "totalRounds", 1, "price", 1000, "rounds", List.of(round("1", customRef(other))))).andExpect(status().isCreated());

        mockMvc.perform(get("/courses/mine").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses").value(hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("내 코스"));
    }
}
