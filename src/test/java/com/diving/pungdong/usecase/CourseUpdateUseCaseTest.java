package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강의 수정(course-update) use-case = 실행 가능한 사양. {@code @DisplayName} 을 위→아래로 읽으면 수정 규칙이 된다.
 *
 * <p><b>이 테스트가 지키는 것.</b> 예전 수정 구현은 저장할 때마다 회차를 통째로 지우고 다시 만들었다. 그래서
 * 수강생이 하나라도 있으면 {@code enrollment_round → course_round} FK 가 걸려 <b>제목만 바꿔도 500</b> 이 났다.
 * 지금은 회차 행을 재사용하고, 진짜로 사라지는 회차에 수강이 물렸을 때만 400(-1024)으로 거절한다.
 *
 * <p>그룹: S* 기본 수정, K* 회차 재사용(id 보존), E* 수강생 물린 강의, V* 검증 거절, R* 권한.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseUpdateUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void cleanUp() {
        enrollmentRepo.deleteAll(); // 회차 FK 를 들고 있어 코스보다 먼저
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─────────── fixture ─────────── */

    private Account instructor(String email) {
        return accountRepo.save(Account.builder().email(email).password("encoded")
                .nickName(email.split("@")[0]).roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
    }

    private Account student(String email) {
        return accountRepo.save(Account.builder().email(email).password("encoded")
                .nickName(email.split("@")[0]).roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String venueRef(Account owner, String name) {
        Venue v = venueRepo.save(Venue.builder().owner(owner).name(name).type(VenueType.OCEAN)
                .lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return "CUSTOM:" + v.getId();
    }

    /** 기본 요청 — 회차 수만큼 같은 위치의 회차를 만든다. */
    private Map<String, Object> body(String title, String venueRef, int totalRounds) {
        Map<String, Object> venue = Map.of("venueRefId", venueRef,
                "tickets", List.of(Map.of("ticketRef", "ticket-1", "daypart", "WEEKDAY")));
        List<Object> rounds = new ArrayList<>();
        for (int i = 1; i <= totalRounds; i++) {
            rounds.add(Map.of("description", i + "회차", "venues", List.of(venue)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("kind", "TRIAL");
        m.put("disciplineCode", "FREEDIVING");
        m.put("totalRounds", totalRounds);
        m.put("price", 100000);
        m.put("rounds", rounds);
        return m;
    }

    private JsonNode createCourse(Account me, Map<String, Object> payload) throws Exception {
        MvcResult res = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private MvcResult update(Account me, long courseId, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(put("/courses/" + courseId).header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andReturn();
    }

    private List<Long> roundIds(JsonNode course) {
        return course.get("rounds").findValues("id").stream().map(JsonNode::asLong).collect(Collectors.toList());
    }

    /** 1회차에 수강 1건을 붙인다(슬롯 스냅샷 없이 회차 참조만 — FK 재현이 목적). */
    private void enroll(Account stu, long courseId, int roundIndex, EnrollmentStatus status) {
        tx.executeWithoutResult(st -> {
            Course course = courseRepo.findById(courseId).orElseThrow();
            CourseRound target = course.getRounds().stream()
                    .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                    .filter(r -> r.getRoundIndex() != null && r.getRoundIndex() == roundIndex)
                    .findFirst().orElseThrow();
            Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(100000)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
            e.getRounds().add(EnrollmentRound.builder().enrollment(e).courseRound(target)
                    .roundIndex(roundIndex).roundKind(RoundKind.REGULAR).status(status)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
            enrollmentRepo.save(e);
        });
    }

    /* ════════════════ S — 기본 수정 ════════════════ */

    @Test
    @DisplayName("S1: 제목과 가격을 바꿔 저장하면 200 이고 바뀐 값이 돌아온다")
    void updateScalars() throws Exception {
        Account me = instructor("s1@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("원본 제목", ref, 2)).get("id").asLong();

        Map<String, Object> payload = body("바뀐 제목", ref, 2);
        payload.put("price", 250000);

        MvcResult res = update(me, id, payload);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("바뀐 제목"))
                .andExpect(jsonPath("$.price").value(250000));
    }

    @Test
    @DisplayName("S2: 회차 설명과 위치를 바꾸면 그대로 반영된다")
    void updateRoundContent() throws Exception {
        Account me = instructor("s2@t.com");
        String oldRef = venueRef(me, "옛 위치");
        String newRef = venueRef(me, "새 위치");
        long id = createCourse(me, body("코스", oldRef, 1)).get("id").asLong();

        update(me, id, body("코스", newRef, 1));

        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.rounds[0].venues[0].venueRefId").value(newRef));
    }

    /* ════════════════ K — 회차 재사용(id 보존) ════════════════ */

    @Test
    @DisplayName("K1: 수정해도 회차 id 가 그대로다 — 회차를 지웠다 다시 만들지 않는다")
    void roundIdsArePreserved() throws Exception {
        Account me = instructor("k1@t.com");
        String ref = venueRef(me, "죽도");
        JsonNode created = createCourse(me, body("코스", ref, 3));
        List<Long> before = roundIds(created);

        MvcResult res = update(me, created.get("id").asLong(), body("제목 변경", ref, 3));
        List<Long> after = roundIds(objectMapper.readTree(res.getResponse().getContentAsString()));

        assertThat(after).containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("K2: 회차를 3개에서 5개로 늘리면 기존 3개 id 는 유지되고 2개만 새로 생긴다")
    void growingRoundsKeepsExistingIds() throws Exception {
        Account me = instructor("k2@t.com");
        String ref = venueRef(me, "죽도");
        JsonNode created = createCourse(me, body("코스", ref, 3));
        List<Long> before = roundIds(created);

        MvcResult res = update(me, created.get("id").asLong(), body("코스", ref, 5));
        List<Long> after = roundIds(objectMapper.readTree(res.getResponse().getContentAsString()));

        assertThat(after).hasSize(5);
        assertThat(after).startsWith(before.get(0), before.get(1), before.get(2));
    }

    @Test
    @DisplayName("K3: 수강생 없는 강의는 회차를 줄일 수 있고, 남은 회차 id 는 유지된다")
    void shrinkingRoundsIsAllowedWithoutEnrollments() throws Exception {
        Account me = instructor("k3@t.com");
        String ref = venueRef(me, "죽도");
        JsonNode created = createCourse(me, body("코스", ref, 3));
        List<Long> before = roundIds(created);

        MvcResult res = update(me, created.get("id").asLong(), body("코스", ref, 1));
        List<Long> after = roundIds(objectMapper.readTree(res.getResponse().getContentAsString()));

        assertThat(after).containsExactly(before.get(0));
    }

    /* ════════════════ E — 수강생이 물린 강의 ════════════════ */

    @Test
    @DisplayName("E1: 수강생이 있어도 제목 수정은 된다 — 예전엔 여기서 500 이 났다")
    void canEditCourseThatHasEnrollments() throws Exception {
        Account me = instructor("e1@t.com");
        Account stu = student("e1s@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("원본", ref, 2)).get("id").asLong();
        enroll(stu, id, 1, EnrollmentStatus.CONFIRMED);

        MvcResult res = update(me, id, body("수정됨", ref, 2));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.title").value("수정됨"));
    }

    @Test
    @DisplayName("E2: 수강생이 있어도 그 회차의 위치를 바꿀 수 있다 — 확정된 예약은 스냅샷이라 안 움직인다")
    void canChangeVenueOfEnrolledRound() throws Exception {
        Account me = instructor("e2@t.com");
        Account stu = student("e2s@t.com");
        String oldRef = venueRef(me, "옛 위치");
        String newRef = venueRef(me, "새 위치");
        long id = createCourse(me, body("코스", oldRef, 1)).get("id").asLong();
        enroll(stu, id, 1, EnrollmentStatus.CONFIRMED);

        MvcResult res = update(me, id, body("코스", newRef, 1));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(enrollmentRepo.findAll()).hasSize(1); // 수강 기록이 살아 있다
    }

    @Test
    @DisplayName("E3: 수강생이 물린 회차를 없애려 하면 400 -1024 로 거절한다")
    void cannotRemoveEnrolledRound() throws Exception {
        Account me = instructor("e3@t.com");
        Account stu = student("e3s@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("코스", ref, 3)).get("id").asLong();
        enroll(stu, id, 3, EnrollmentStatus.CONFIRMED); // 3회차에 수강생

        mockMvc.perform(put("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("코스", ref, 2)))) // 3회차 제거 시도
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1024));

        mockMvc.perform(get("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.totalRounds").value(3)); // 거절됐으니 그대로
    }

    @Test
    @DisplayName("E4: 수강생이 안 물린 회차만 줄이는 건 허용된다")
    void canRemoveRoundNobodyEnrolledIn() throws Exception {
        Account me = instructor("e4@t.com");
        Account stu = student("e4s@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("코스", ref, 3)).get("id").asLong();
        enroll(stu, id, 1, EnrollmentStatus.CONFIRMED); // 1회차만 물림

        MvcResult res = update(me, id, body("코스", ref, 2)); // 3회차만 제거

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("E5: 취소된 수강도 회차를 붙들고 있다 — 상태와 무관하게 거절한다")
    void cancelledEnrollmentStillBlocksRemoval() throws Exception {
        Account me = instructor("e5@t.com");
        Account stu = student("e5s@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("코스", ref, 2)).get("id").asLong();
        enroll(stu, id, 2, EnrollmentStatus.CANCELLED);

        mockMvc.perform(put("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("코스", ref, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1024));
    }

    /* ════════════════ V — 검증 거절 ════════════════ */

    @Test
    @DisplayName("V1: rounds 개수가 totalRounds 와 다르면 400")
    void roundCountMustMatchTotalRounds() throws Exception {
        Account me = instructor("v1@t.com");
        String ref = venueRef(me, "죽도");
        long id = createCourse(me, body("코스", ref, 2)).get("id").asLong();

        Map<String, Object> broken = body("코스", ref, 2);
        broken.put("totalRounds", 5); // rounds 는 2개인데 5라고 주장

        mockMvc.perform(put("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(broken)))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 남의 강의는 수정할 수 없고 존재를 숨긴다(400 -1009)")
    void cannotUpdateSomeoneElsesCourse() throws Exception {
        Account owner = instructor("r1a@t.com");
        Account other = instructor("r1b@t.com");
        String ref = venueRef(owner, "죽도");
        long id = createCourse(owner, body("남의 코스", ref, 1)).get("id").asLong();

        mockMvc.perform(put("/courses/" + id).header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("가로채기", ref, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
    }
}
