package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilityWindow;
import com.diving.pungdong.availability.AvailabilityWindowJpaRepo;
import com.diving.pungdong.availability.RecurrenceMode;
import com.diving.pungdong.availability.dto.AvailabilityCreateRequest;
import com.diving.pungdong.availability.dto.AvailabilityUpdateRequest;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 가용시간 캘린더(availability) use-case 시나리오 — 실제 H2 + Spring Security 필터 체인 + 실제
 * 서비스/JPA. EmbeddedRedis 불필요(Redis 경로 안 탐).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 읽으면 사양. 그룹 — S* 성공(생성/조회/수정/삭제) /
 * H* 점유 hold(추가·정원 자동확장·제거·상태 파생) / G* 게이트 / R* 권한·격리 / V* 검증거절.
 *
 * <p>핵심 정책: 2층 모델(window=가용시간 / hold=외부·수동 점유). v1 은 enrollment 미연동이라 풍덩 수강생
 * 점유(pending/confirmed)는 항상 0 — 캘린더는 AVAILABLE ↔ EXTERNAL/FULL 만 그린다. 게이트 = 강사신청
 * 보유(상태 무관, SUBMITTED 포함). ⚠️ 이 레포는 {@code Authorization} 헤더에 raw JWT(Bearer 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvailabilityUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AvailabilityWindowJpaRepo windowRepo;
    @Autowired AvailabilityHoldJpaRepo holdRepo;

    /** 미래의 한 주 월요일 — 전개가 과거일에 막히지 않게 고정 미래 + 월요일로 정규화. */
    private static final LocalDate MON =
            LocalDate.of(2030, 1, 7).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    @AfterEach
    void cleanUp() {
        holdRepo.deleteAll();
        windowRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─────────────────────────────────────────── */

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    /** 강사 트랙 진입(리뷰 대기 = SUBMITTED) — 가용시간 게이트 통과 조건. 종목 무관. */
    private void enterInstructorTrack(Account account) {
        applicationRepo.save(InstructorApplication.builder()
                .account(account).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AvailabilityCreateRequest once(LocalDate date) {
        return AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.ONCE).date(date)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).capacity(4).build();
    }

    /** "내 가용시간 1개"를 만들고 그 id 를 돌려준다 — hold/수정/삭제 시나리오의 준비. */
    private long createOneWindow(Account instructor, String token) throws Exception {
        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(once(MON))))
                .andExpect(status().isCreated());
        return windowRepo.findAll().get(0).getId();
    }

    /* ─── S* 성공(생성/조회/수정/삭제) ──────────────────────── */

    @Test
    @DisplayName("S1 강사가 ONCE 로 가용시간을 만들면 그 하루에 window 1개가 생기고 상태는 AVAILABLE 이다")
    void createOnce() throws Exception {
        Account in = account("in1@pd.com", "강사1");
        enterInstructorTrack(in);

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(once(MON))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.windows.length()").value(1))
                .andExpect(jsonPath("$._embedded.windows[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$._embedded.windows[0].capacity").value(4));

        List<AvailabilityWindow> all = windowRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDate()).isEqualTo(MON);
    }

    @Test
    @DisplayName("S2 WEEKLY 로 월·수·금을 고르면 그 주 세 날짜에 window 3개가 만들어진다")
    void createWeekly() throws Exception {
        Account in = account("in2@pd.com", "강사2");
        enterInstructorTrack(in);
        AvailabilityCreateRequest req = AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.WEEKLY).date(MON)
                .dayOfWeeks(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
                .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(18, 0)).capacity(4).build();

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.windows.length()").value(3));

        assertThat(windowRepo.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("S3 FOUR_WEEKS 로 월요일을 고르면 4주에 걸쳐 window 4개가 만들어진다")
    void createFourWeeks() throws Exception {
        Account in = account("in3@pd.com", "강사3");
        enterInstructorTrack(in);
        AvailabilityCreateRequest req = AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.FOUR_WEEKS).date(MON)
                .dayOfWeeks(List.of(DayOfWeek.MONDAY))
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(14, 0)).capacity(3).build();

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.windows.length()").value(4));

        assertThat(windowRepo.findAll()).hasSize(4);
    }

    @Test
    @DisplayName("S4 캘린더를 from~to 범위로 읽으면 그 범위 안의 내 가용시간이 날짜순으로 나온다")
    void listRange() throws Exception {
        Account in = account("in4@pd.com", "강사4");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        createOneWindow(in, token);

        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("from", MON.minusDays(1).toString())
                .param("to", MON.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.windows.length()").value(1))
                .andExpect(jsonPath("$._embedded.windows[0].date").value(MON.toString()));
    }

    @Test
    @DisplayName("S5 가용시간을 수정하면 시간·정원이 반영된다")
    void updateWindow() throws Exception {
        Account in = account("in5@pd.com", "강사5");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token);

        AvailabilityUpdateRequest req = AvailabilityUpdateRequest.builder()
                .date(MON).startTime(LocalTime.of(12, 0)).endTime(LocalTime.of(20, 0)).capacity(6).build();

        mockMvc.perform(put("/instructor/availability/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(6));

        AvailabilityWindow w = windowRepo.findById(id).orElseThrow();
        assertThat(w.getStartTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(w.getCapacity()).isEqualTo(6);
    }

    @Test
    @DisplayName("S6 가용시간을 삭제하면 캘린더에서 사라진다")
    void deleteWindow() throws Exception {
        Account in = account("in6@pd.com", "강사6");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token);

        mockMvc.perform(delete("/instructor/availability/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        assertThat(windowRepo.findById(id)).isEmpty();
    }

    /* ─── H* 점유 hold ─────────────────────────────────────── */

    @Test
    @DisplayName("H1 외부예약(메모+인원)을 추가하면 외부 점유가 잡히고 상태가 EXTERNAL 이 된다")
    void addExternalHold() throws Exception {
        Account in = account("h1@pd.com", "강사h1");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token);

        HoldRequest req = HoldRequest.builder().count(2).memo("네이버 단체 2명").build();
        mockMvc.perform(post("/instructor/availability/{id}/holds", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXTERNAL"))
                .andExpect(jsonPath("$.externalCount").value(2))
                .andExpect(jsonPath("$.filled").value(2));

        List<com.diving.pungdong.availability.AvailabilityHold> holds = holdRepo.findByWindowId(id);
        assertThat(holds).hasSize(1);
        assertThat(holds.get(0).getCount()).isEqualTo(2);
        assertThat(holds.get(0).getMemo()).isEqualTo("네이버 단체 2명");
    }

    @Test
    @DisplayName("H2 점유가 정원을 넘기면 정원이 자동 확장되고 상태가 FULL 이 된다")
    void holdAutoExpandsCapacity() throws Exception {
        Account in = account("h2@pd.com", "강사h2");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token); // capacity 4

        mockMvc.perform(post("/instructor/availability/{id}/holds", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(HoldRequest.builder().count(6).memo("대형 단체").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FULL"))
                .andExpect(jsonPath("$.capacity").value(6))
                .andExpect(jsonPath("$.filled").value(6));
    }

    @Test
    @DisplayName("H3 ± 빠른조정(메모 없는 점유)을 더했다 빼면 다시 AVAILABLE 로 돌아온다")
    void quickAdjustThenRemove() throws Exception {
        Account in = account("h3@pd.com", "강사h3");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token);

        // + 빠른조정 (memo 없음)
        mockMvc.perform(post("/instructor/availability/{id}/holds", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(HoldRequest.builder().count(1).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXTERNAL"))
                .andExpect(jsonPath("$.holds.length()").value(1));

        long holdId = holdRepo.findByWindowId(id).get(0).getId();

        mockMvc.perform(delete("/instructor/availability/{id}/holds/{holdId}", id, holdId)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.filled").value(0));

        assertThat(holdRepo.findByWindowId(id)).isEmpty();
    }

    /* ─── G* 게이트 ─────────────────────────────────────────── */

    @Test
    @DisplayName("G1 강사신청이 없는 사용자가 가용시간을 만들려 하면 400(강사 트랙 밖)")
    void gateRejectsNonInstructor() throws Exception {
        Account student = account("stu@pd.com", "수강생"); // 강사신청 없음

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON).content(json(once(MON))))
                .andExpect(status().isBadRequest());

        assertThat(windowRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("G0 토큰 없이 가용시간 API 를 부르면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/instructor/availability")
                .contentType(MediaType.APPLICATION_JSON).content(json(once(MON))))
                .andExpect(status().isUnauthorized());
    }

    /* ─── R* 권한·격리 ─────────────────────────────────────── */

    @Test
    @DisplayName("R1 남의 가용시간을 조회하면 400(존재 숨김)")
    void cannotReadOthersWindow() throws Exception {
        Account owner = account("own@pd.com", "주인");
        enterInstructorTrack(owner);
        long id = createOneWindow(owner, tokenFor(owner));

        Account other = account("oth@pd.com", "남");
        enterInstructorTrack(other);

        mockMvc.perform(get("/instructor/availability/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R2 남의 가용시간에 외부예약을 추가하려 하면 400")
    void cannotHoldOthersWindow() throws Exception {
        Account owner = account("own2@pd.com", "주인2");
        enterInstructorTrack(owner);
        long id = createOneWindow(owner, tokenFor(owner));

        Account other = account("oth2@pd.com", "남2");
        enterInstructorTrack(other);

        mockMvc.perform(post("/instructor/availability/{id}/holds", id)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(HoldRequest.builder().count(1).memo("끼어들기").build())))
                .andExpect(status().isBadRequest());

        assertThat(holdRepo.findByWindowId(id)).isEmpty();
    }

    /* ─── V* 검증거절 ──────────────────────────────────────── */

    @Test
    @DisplayName("V1 끝시간이 시작시간보다 빠르면(또는 같으면) 400")
    void rejectsInvertedTime() throws Exception {
        Account in = account("v1@pd.com", "강사v1");
        enterInstructorTrack(in);
        AvailabilityCreateRequest req = AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.ONCE).date(MON)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(10, 0)).capacity(4).build();

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 정원이 1 미만이면 400")
    void rejectsNonPositiveCapacity() throws Exception {
        Account in = account("v2@pd.com", "강사v2");
        enterInstructorTrack(in);
        AvailabilityCreateRequest req = AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.ONCE).date(MON)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).capacity(0).build();

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V3 WEEKLY 인데 요일을 하나도 안 고르면 400")
    void rejectsWeeklyWithoutDays() throws Exception {
        Account in = account("v3@pd.com", "강사v3");
        enterInstructorTrack(in);
        AvailabilityCreateRequest req = AvailabilityCreateRequest.builder()
                .mode(RecurrenceMode.WEEKLY).date(MON).dayOfWeeks(List.of())
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).capacity(4).build();

        mockMvc.perform(post("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in))
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V4 정원을 현재 점유보다 낮게 수정하려 하면 400")
    void rejectsShrinkBelowOccupancy() throws Exception {
        Account in = account("v4@pd.com", "강사v4");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = createOneWindow(in, token);
        mockMvc.perform(post("/instructor/availability/{id}/holds", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(HoldRequest.builder().count(3).memo("단체").build())))
                .andExpect(status().isCreated());

        AvailabilityUpdateRequest req = AvailabilityUpdateRequest.builder()
                .date(MON).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(18, 0)).capacity(2).build();

        mockMvc.perform(put("/instructor/availability/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }
}
