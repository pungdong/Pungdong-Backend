package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueDaypart;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueTicket;
import com.diving.pungdong.venue.VenueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
 * 공개 강의 상세(GET /courses/{id}/detail) use-case = 실행 가능한 사양. 둘러보기 카드 → 상세(OPEN 누구나).
 * 강사용 GET /courses/{id} 와 달리 venue 를 합성: 위치명·<b>입장료(이용권×평일/주말 daypart fee)</b>·장비.
 * {@code @DisplayName} 위→아래로 읽으면 규칙.
 *
 * <p>그룹: S* 상세 합성, V* 비공개/없음. CUSTOM 위치(이용권 평일/주말 fee 다르게)를 직접 seed 해 입장료
 * 합성을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseDetailUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;

    @AfterEach
    void cleanUp() {
        courseRepo.deleteAll();
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

    /** 이용권 1개(평일 fee / 주말 fee 다르게)를 가진 CUSTOM 위치 seed. returns [venueRefId, ticketRef]. */
    private String[] seedVenueWithTicket(Account owner, int weekdayFee, int weekendFee) {
        VenueTicket t = VenueTicket.builder()
                .name("일반권 (3시간)").sortOrder(0)
                .disciplineCodes(new HashSet<>(Set.of("FREEDIVING"))).build();
        t.addDaypart(VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(weekdayFee).timeMode(TimeMode.FIXED).build());
        t.addDaypart(VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(weekendFee).timeMode(TimeMode.SAME).build());
        Venue v = Venue.builder()
                .owner(owner).name("잠실 잠수풀").type(VenueType.SWIMMING_POOL)
                .address("서울특별시 송파구 올림픽로 25").lockedDisciplineCode("FREEDIVING")
                .createdAt(LocalDateTime.now()).build();
        v.addTicket(t);
        venueRepo.save(v);
        return new String[]{"CUSTOM:" + v.getId(), t.getRef()};
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    /** 코스 작성(POST) → OPEN 전이. 그 위치(venueRefId)에서 이용권(ticketRef)을 평일에 쓴다. */
    private long openCourse(Account me, String venueRefId, String ticketRef) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "AIDA2 프리다이빙 과정");
        body.put("kind", "CERTIFICATION");
        body.put("organizationCode", "AIDA");
        body.put("levels", List.of("LEVEL_2"));
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 350000);
        body.put("totalRounds", 1);
        body.put("description", "자유잠수 L2 과정");
        body.put("rounds", List.of(Map.of("description", "1회차 적응",
                "venues", List.of(Map.of("venueRefId", venueRefId,
                        "tickets", List.of(Map.of("ticketRef", ticketRef, "daypart", "WEEKDAY")))))));
        String loc = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(loc, "$.id")).longValue();
        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isOk());
        return id;
    }

    /* ════════════════ S — 상세 합성 ════════════════ */

    @Test
    @DisplayName("S1 OPEN 코스 공개 상세는 비로그인으로 정체성·강사·회차가 온다")
    void s1_public_detail() throws Exception {
        Account inst = account("s1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))  // 인증 헤더 없음
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.price").value(350000))
                .andExpect(jsonPath("$.instructorName").value("s1"))
                .andExpect(jsonPath("$.rounds[0].roundIndex").value(1))
                .andExpect(jsonPath("$.rounds[0].venueRefIds[0]").value(ref[0]));
    }

    @Test
    @DisplayName("S2 입장료 합성 — 위치 이용권의 평일/주말 fee 가 daypart 별로 정확히 온다(단일 entry 아님)")
    void s2_entry_fee_by_daypart() throws Exception {
        Account inst = account("s2@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venues[0].name").value("잠실 잠수풀"))
                .andExpect(jsonPath("$.venues[0].area").value(startsWith("서울")))
                .andExpect(jsonPath("$.venues[0].tickets[0].ticketName").value("일반권 (3시간)"))
                // 평일 48,000 / 주말 55,000 — daypart 별 fee
                .andExpect(jsonPath("$.venues[0].tickets[0].fees[?(@.daypart=='WEEKDAY')].fee").value(hasItem(48000)))
                .andExpect(jsonPath("$.venues[0].tickets[0].fees[?(@.daypart=='WEEKEND')].fee").value(hasItem(55000)));
    }

    /* ════════════════ V — 비공개 / 없음 ════════════════ */

    @Test
    @DisplayName("V1 DRAFT(미공개) 코스 상세는 400 (존재 숨김)")
    void v1_draft_hidden() throws Exception {
        Account inst = account("v1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        // 코스 생성만, OPEN 안 함 → DRAFT
        Map<String, Object> body = new HashMap<>();
        body.put("title", "임시 과정");
        body.put("kind", "TRIAL");
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", ref[0],
                        "tickets", List.of(Map.of("ticketRef", ref[1], "daypart", "WEEKDAY")))))));
        String loc = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(loc, "$.id")).longValue();

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 없는 코스 id 상세는 400")
    void v2_missing_id() throws Exception {
        mockMvc.perform(get("/courses/999999/detail"))
                .andExpect(status().isBadRequest());
    }
}
