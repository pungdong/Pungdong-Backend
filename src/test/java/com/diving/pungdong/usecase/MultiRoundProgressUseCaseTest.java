package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.venue.*;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 다회차 진행 use-case — 2회차+ 일정 신청 · 순차 게이트 · 강사 일정변경요청(제안→pick). {@code @DisplayName} = 사양.
 *
 * <p>실 H2 + 시큐리티 + 실 서비스. 2 정규회차 코스 + 두 날짜 coverage. round1 CONFIRMED 는 결제 흐름 대신 repo 로
 * 박아 게이트(직전 CONFIRMED)를 격리 검증. ⚠️ Authorization raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiRoundProgressUseCaseTest {

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalDate D2 = LocalDate.now().plusWeeks(2);
    private static final LocalTime START = LocalTime.of(14, 0);
    private static final LocalTime END = LocalTime.of(17, 0);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;

    @AfterEach
    void clean() {
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick, Role role) {
        return accountRepo.save(Account.builder().email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private Account instructor(String email, String nick, int cap) {
        Account ins = account(email, nick, Role.INSTRUCTOR);
        ins.setDefaultCapacity(cap);
        accountRepo.save(ins);
        applicationRepo.save(InstructorApplication.builder().account(ins).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
        return ins;
    }

    private Venue venue(Account owner) {
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueTicket ticket = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday); ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(LocalDateTime.now()).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    /** 2 정규회차 코스(둘 다 같은 venue/ticket). */
    private Course twoRoundCourse(Account ins, String venueRef, String ticketRef) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(LocalDateTime.now()).build();
        course.addRound(courseRound(1, venueRef, ticketRef));
        course.addRound(courseRound(2, venueRef, ticketRef));
        return courseRepo.save(course);
    }

    private CourseRound courseRound(int idx, String venueRef, String ticketRef) {
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(idx).build();
        RoundVenue rv = RoundVenue.builder().venueRefId(venueRef).sortOrder(0).build();
        rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(rv);
        return round;
    }

    private void openCoverage(Account ins, LocalDate date) {
        coverageRepo.save(AvailabilityCoverage.builder().instructor(ins).date(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** round1 신청(D1) → repo 로 CONFIRMED 박기. 반환 = enrollmentId. */
    private Long enrollWithConfirmedRound1(Account stu, Course course, String venueRef, String ticketRef) throws Exception {
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", venueRef, "ticketRef", ticketRef,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
        r1.setStatus(EnrollmentStatus.CONFIRMED);
        roundRepo.save(r1);
        return enrollmentRepo.findByStudentIdOrderByIdDesc(stu.getId()).get(0).getId();
    }

    private String roundBody(String venueRef, String ticketRef, LocalDate date) {
        return json(Map.of("date", date.toString(), "venueRefId", venueRef, "ticketRef", ticketRef,
                "blockStart", START.toString(), "blockEnd", END.toString()));
    }

    /* ─── M* 다회차 진행 ─── */

    @Test
    @DisplayName("M1 직전 정규회차가 CONFIRMED 가 아니면 다음 회차 신청은 막힌다(순차 게이트 400)")
    void gateBlocksNextRoundBeforeConfirmed() throws Exception {
        Account ins = instructor("ins-m1@pd.com", "강사M1", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-m1@pd.com", "학생M1", Role.STUDENT);

        // round1 PENDING(미확정) 상태로 둠
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", ticket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        Long enrollmentId = enrollmentRepo.findByStudentIdOrderByIdDesc(stu.getId()).get(0).getId();

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isBadRequest()); // 1회차 미확정 → 잠김
    }

    @Test
    @DisplayName("M2 직전 회차 CONFIRMED 면 다음 회차를 PENDING 으로 신청할 수 있다(roundIndex 2)")
    void schedulesNextRoundAfterConfirmed() throws Exception {
        Account ins = instructor("ins-m2@pd.com", "강사M2", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-m2@pd.com", "학생M2", Role.STUDENT);
        Long enrollmentId = enrollWithConfirmedRound1(stu, course, ref, ticket);

        mockMvc.perform(get("/enrollments/{id}/next-options", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.course.roundLabel").value("2회차"))
                .andExpect(jsonPath("$.slots.length()").value(2)); // coverage 열린 D1·D2 둘 다(D1 은 round1 점유 후 잔여)

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roundIndex").value(2))
                .andExpect(jsonPath("$.total").value(15000)); // 2회차는 수강료 없음(부대비용만)
    }

    @Test
    @DisplayName("M3 강사 일정변경요청 → 학생이 제안 날짜 선택하면 사전 수락이라 곧장 PAYMENT_PENDING")
    void rescheduleProposeThenPick() throws Exception {
        Account ins = instructor("ins-m3@pd.com", "강사M3", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, d3);
        Account stu = account("stu-m3@pd.com", "학생M3", Role.STUDENT);
        Long enrollmentId = enrollWithConfirmedRound1(stu, course, ref, ticket);

        // 2회차 신청(D2)
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        // 강사 일정변경요청 — D2 대신 d3 제안
        mockMvc.perform(post("/instructor/enrollments/{id}/propose-dates", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("dates", List.of(d3.toString())))))
                .andExpect(status().isOk());

        // hub 에 RESCHEDULING + 제안 날짜
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[1].status").value("RESCHEDULING"))
                .andExpect(jsonPath("$.courses[0].rounds[1].proposedDates[0]").value(d3.toString()));

        // 학생이 d3 선택 → 사전 수락 → PAYMENT_PENDING
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-date", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("date", d3.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.date").value(d3.toString()));
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("M4 진행 중 회차(2회차)는 강사가 거절할 수 없다(일정변경요청만) — 400")
    void rejectBlockedForNonFirstRound() throws Exception {
        Account ins = instructor("ins-m4@pd.com", "강사M4", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-m4@pd.com", "학생M4", Role.STUDENT);
        Long enrollmentId = enrollWithConfirmedRound1(stu, course, ref, ticket);

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        mockMvc.perform(post("/instructor/enrollments/{id}/reject", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"안돼요\"}"))
                .andExpect(status().isBadRequest()); // 거절은 1회차만
    }
}
