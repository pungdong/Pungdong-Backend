package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.dto.CapacityRequest;
import com.diving.pungdong.availability.dto.CoverageRequest;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.availability.dto.SessionCreateRequest;
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
 * 강사 캘린더(coverage/session 2레이어) use-case — 실 H2 + Spring Security 필터 + 실 서비스/JPA.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. CV* = coverage(예약가능시간: 머지/분할/일정보호) /
 * SS* = session(일정: 원자추가·누적·삭제) / CAL* = 분리 조회 / C* = 정원(계정 baseline + override) /
 * G* 게이트 / V* 검증.
 *
 * <p>모델: coverage = 순수 시간 띠(머지 정규화), session = 위치·정원·점유. 일정 원자추가 = coverage 확장+머지
 * 후 session 생성/join 1트랜잭션. coverage 닫기가 일정 가로지르면 -1014 거부. 정원 = 계정 기본값 + session
 * override. ⚠️ {@code Authorization} 헤더는 raw JWT(Bearer 없음). 위치 없는 session 으로 venue 셋업 회피.
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
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired AvailabilityHoldJpaRepo holdRepo;

    private static final LocalDate MON =
            LocalDate.of(2030, 1, 7).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    @AfterEach
    void cleanUp() {
        holdRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

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

    /** coverage 열기 — POST /coverage. */
    private void openCoverage(String token, LocalTime s, LocalTime e) throws Exception {
        mockMvc.perform(post("/instructor/availability/coverage")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(CoverageRequest.builder().date(MON).startTime(s).endTime(e).build())))
                .andExpect(status().isOk());
    }

    /** 일정 원자 추가 — POST /sessions (위치 없는 session). */
    private long addSession(String token, LocalTime s, LocalTime e, Integer count) throws Exception {
        mockMvc.perform(post("/instructor/availability/sessions")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(SessionCreateRequest.builder().date(MON).startTime(s).endTime(e).count(count).build())))
                .andExpect(status().isCreated());
        return sessionRepo.findAll().stream()
                .filter(x -> x.getStartTime().equals(s) && x.getEndTime().equals(e)).findFirst().orElseThrow().getId();
    }

    private String capacityBody(int c) {
        return json(CapacityRequest.builder().capacity(c).build());
    }

    /* ─── CV* coverage ─── */

    @Test
    @DisplayName("CV1 떨어진 두 구간(10–12, 14–20)을 열면 머지 안 되고 coverage 2개로 남는다")
    void coverageStaysSeparate() throws Exception {
        Account in = account("cv1@pd.com", "강사cv1");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        openCoverage(token, LocalTime.of(10, 0), LocalTime.of(12, 0));
        openCoverage(token, LocalTime.of(14, 0), LocalTime.of(20, 0));
        assertThat(coverageRepo.findByInstructorIdAndDate(in.getId(), MON)).hasSize(2);
    }

    @Test
    @DisplayName("CV2 맞닿는 두 구간(10–12 + 12–14)을 열면 10–14 한 구간으로 머지된다")
    void coverageMergesTouching() throws Exception {
        Account in = account("cv2@pd.com", "강사cv2");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        openCoverage(token, LocalTime.of(10, 0), LocalTime.of(12, 0));
        openCoverage(token, LocalTime.of(12, 0), LocalTime.of(14, 0));
        var ranges = coverageRepo.findByInstructorIdAndDate(in.getId(), MON);
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(ranges.get(0).getEndTime()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("CV3 10–20 에서 12–14 를 닫으면 10–12·14–20 두 구간으로 분할된다")
    void coverageSplitsOnClose() throws Exception {
        Account in = account("cv3@pd.com", "강사cv3");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        openCoverage(token, LocalTime.of(10, 0), LocalTime.of(20, 0));
        mockMvc.perform(delete("/instructor/availability/coverage")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(CoverageRequest.builder().date(MON)
                        .startTime(LocalTime.of(12, 0)).endTime(LocalTime.of(14, 0)).build())))
                .andExpect(status().isOk());
        assertThat(coverageRepo.findByInstructorIdAndDate(in.getId(), MON)).hasSize(2);
    }

    @Test
    @DisplayName("CV4 그 구간에 일정이 걸쳐 있으면 coverage 를 닫을 수 없다(-1014, BE 자동처리 X)")
    void closeRejectedWhenSessionInside() throws Exception {
        Account in = account("cv4@pd.com", "강사cv4");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        addSession(token, LocalTime.of(14, 0), LocalTime.of(16, 0), 1); // coverage 14–16 + 일정

        mockMvc.perform(delete("/instructor/availability/coverage")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(CoverageRequest.builder().date(MON)
                        .startTime(LocalTime.of(13, 0)).endTime(LocalTime.of(17, 0)).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1014));
        // coverage 그대로
        assertThat(coverageRepo.findByInstructorIdAndDate(in.getId(), MON)).isNotEmpty();
    }

    /* ─── SS* session ─── */

    @Test
    @DisplayName("SS1 커버리지 없는 허공(12–14)에 일정을 추가하면 coverage 가 먼저 생기고 일정이 얹힌다(1 API)")
    void addSessionLiftsCoverage() throws Exception {
        Account in = account("ss1@pd.com", "강사ss1");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        addSession(token, LocalTime.of(12, 0), LocalTime.of(14, 0), 1);
        // coverage 12–14 생성
        var ranges = coverageRepo.findByInstructorIdAndDate(in.getId(), MON);
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).getStartTime()).isEqualTo(LocalTime.of(12, 0));
        // 일정 1개
        assertThat(sessionRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("SS2 같은 (위치,시간)에 외부예약을 또 추가하면 새 일정이 아니라 기존 일정에 점유가 누적된다")
    void sameSlotAccumulatesHold() throws Exception {
        Account in = account("ss2@pd.com", "강사ss2");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id1 = addSession(token, LocalTime.of(14, 0), LocalTime.of(18, 0), 2);
        long id2 = addSession(token, LocalTime.of(14, 0), LocalTime.of(18, 0), 1);
        assertThat(id1).isEqualTo(id2); // 같은 session
        assertThat(sessionRepo.findAll()).hasSize(1);
        assertThat(holdRepo.findBySessionId(id1)).hasSize(2); // 점유 2건 누적
    }

    @Test
    @DisplayName("SS3 일정 정원은 계정 기본값(4)을 따르고, override 로 그 일정만 고정할 수 있다")
    void sessionCapacityDefaultThenOverride() throws Exception {
        Account in = account("ss3@pd.com", "강사ss3");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = addSession(token, LocalTime.of(10, 0), LocalTime.of(12, 0), 1);

        mockMvc.perform(get("/instructor/availability/sessions/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.capacityOverridden").value(false));

        mockMvc.perform(patch("/instructor/availability/sessions/{id}/capacity", id)
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(capacityBody(2)))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.capacityOverridden").value(true));
    }

    @Test
    @DisplayName("SS4 일정을 삭제해도 coverage 는 그대로 남는다(독립)")
    void deleteSessionKeepsCoverage() throws Exception {
        Account in = account("ss4@pd.com", "강사ss4");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = addSession(token, LocalTime.of(14, 0), LocalTime.of(16, 0), 1);

        mockMvc.perform(delete("/instructor/availability/sessions/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());
        assertThat(sessionRepo.findById(id)).isEmpty();
        assertThat(coverageRepo.findByInstructorIdAndDate(in.getId(), MON)).isNotEmpty(); // coverage 유지
    }

    @Test
    @DisplayName("SS5 외부 점유를 모두 제거하면(0명) 빈 일정은 자동으로 사라진다(204). coverage 는 유지")
    void removingLastHoldDeletesEmptySession() throws Exception {
        Account in = account("ss5@pd.com", "강사ss5");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = addSession(token, LocalTime.of(14, 0), LocalTime.of(16, 0), 2); // hold 2명
        long holdId = holdRepo.findBySessionId(id).get(0).getId();

        mockMvc.perform(delete("/instructor/availability/sessions/{id}/holds/{holdId}", id, holdId)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent()); // 점유 0 → 빈 일정 삭제
        assertThat(sessionRepo.findById(id)).isEmpty();
        assertThat(coverageRepo.findByInstructorIdAndDate(in.getId(), MON)).isNotEmpty();
    }

    @Test
    @DisplayName("SS6 같은 강사의 기존 일정과 시간이 겹치는 새 일정은 거부(-1015). 맞닿는 건 허용")
    void rejectsOverlappingSession() throws Exception {
        Account in = account("ss6@pd.com", "강사ss6");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        addSession(token, LocalTime.of(14, 0), LocalTime.of(16, 0), 1); // 기존 14–16

        // 13–17 (14–16 을 포함, 겹침) → 400 -1015
        mockMvc.perform(post("/instructor/availability/sessions")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(SessionCreateRequest.builder().date(MON)
                        .startTime(LocalTime.of(13, 0)).endTime(LocalTime.of(17, 0)).count(1).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1015));

        // 16–18 (14–16 과 맞닿음, 안 겹침) → 201
        mockMvc.perform(post("/instructor/availability/sessions")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(SessionCreateRequest.builder().date(MON)
                        .startTime(LocalTime.of(16, 0)).endTime(LocalTime.of(18, 0)).count(1).build())))
                .andExpect(status().isCreated());
    }

    /* ─── CAL* 분리 조회 ─── */

    @Test
    @DisplayName("CAL1 캘린더 범위 조회는 coverage[] 와 sessions[] 로 분리되어 나온다")
    void calendarSplit() throws Exception {
        Account in = account("cal1@pd.com", "강사cal1");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        openCoverage(token, LocalTime.of(10, 0), LocalTime.of(20, 0));
        addSession(token, LocalTime.of(14, 0), LocalTime.of(18, 0), 1);

        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("from", MON.minusDays(1).toString()).param("to", MON.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.length()").value(1))
                .andExpect(jsonPath("$.coverage[0].startTime").value("10:00:00"))
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].status").value("EXTERNAL"));
    }

    /* ─── C* 정원 ─── */

    @Test
    @DisplayName("C1 신규 강사의 기본 정원은 4 이다")
    void defaultCapacityIsFour() throws Exception {
        Account in = account("c1@pd.com", "강사c1");
        enterInstructorTrack(in);
        mockMvc.perform(get("/instructor/availability/settings")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in)))
                .andExpect(jsonPath("$.defaultCapacity").value(4));
    }

    @Test
    @DisplayName("C2 기본 정원을 5로 바꾸면 override 없는 일정이 즉시 5를 따른다(라이브)")
    void defaultChangePropagatesLive() throws Exception {
        Account in = account("c2@pd.com", "강사c2");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = addSession(token, LocalTime.of(10, 0), LocalTime.of(12, 0), 1);

        mockMvc.perform(patch("/instructor/availability/settings")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(capacityBody(5)))
                .andExpect(jsonPath("$.defaultCapacity").value(5));

        mockMvc.perform(get("/instructor/availability/sessions/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.capacity").value(5))
                .andExpect(jsonPath("$.capacityOverridden").value(false));
    }

    /* ─── G* 게이트 / V* 검증 ─── */

    @Test
    @DisplayName("G0 토큰 없이 캘린더 API 를 부르면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/instructor/availability/coverage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(CoverageRequest.builder().date(MON)
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(12, 0)).build())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("G1 강사신청이 없는 사용자가 일정을 만들려 하면 400(강사 트랙 밖)")
    void gateRejectsNonInstructor() throws Exception {
        Account student = account("g1@pd.com", "수강생");
        mockMvc.perform(post("/instructor/availability/sessions")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student)).contentType(MediaType.APPLICATION_JSON)
                .content(json(SessionCreateRequest.builder().date(MON)
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(12, 0)).build())))
                .andExpect(status().isBadRequest());
        assertThat(sessionRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V1 끝시간이 시작보다 빠르면 400")
    void rejectsInvertedTime() throws Exception {
        Account in = account("v1@pd.com", "강사v1");
        enterInstructorTrack(in);
        mockMvc.perform(post("/instructor/availability/coverage")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(in)).contentType(MediaType.APPLICATION_JSON)
                .content(json(CoverageRequest.builder().date(MON)
                        .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(10, 0)).build())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 일정 정원 override 를 1 미만으로 설정하려 하면 400")
    void rejectsNonPositiveOverride() throws Exception {
        Account in = account("v2@pd.com", "강사v2");
        enterInstructorTrack(in);
        String token = tokenFor(in);
        long id = addSession(token, LocalTime.of(10, 0), LocalTime.of(12, 0), 1);
        mockMvc.perform(patch("/instructor/availability/sessions/{id}/capacity", id)
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(capacityBody(0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R1 남의 일정을 조회하면 400(존재 숨김)")
    void cannotReadOthersSession() throws Exception {
        Account owner = account("r1a@pd.com", "주인");
        enterInstructorTrack(owner);
        long id = addSession(tokenFor(owner), LocalTime.of(10, 0), LocalTime.of(12, 0), 1);

        Account other = account("r1b@pd.com", "남");
        enterInstructorTrack(other);
        mockMvc.perform(get("/instructor/availability/sessions/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isBadRequest());
    }
}
