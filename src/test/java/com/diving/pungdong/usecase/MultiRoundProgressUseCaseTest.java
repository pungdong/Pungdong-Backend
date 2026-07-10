package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.EnrollmentExpiryService;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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
    @Autowired com.diving.pungdong.identityverification.IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired AvailabilityHoldJpaRepo holdRepo;
    @Autowired EnrollmentExpiryService expiryService;

    @AfterEach
    void clean() {
        holdRepo.deleteAll();
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        identityVerificationRepo.deleteAll(); // account FK — 계정 삭제 전
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick, Role role) {
        Account a = accountRepo.save(Account.builder().email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
        identityVerificationRepo.save(com.diving.pungdong.identityverification.IdentityVerification.builder()
                .account(a).status(com.diving.pungdong.identityverification.IdentityVerificationStatus.VERIFIED)
                .verifiedAt(OffsetDateTime.now(ZoneOffset.UTC)).build()); // 수강신청 게이트 통과용 본인인증
        return a;
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
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
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
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    /** 2 정규회차 코스(둘 다 같은 venue/ticket). */
    private Course twoRoundCourse(Account ins, String venueRef, String ticketRef) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
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

    /** 두 번째 venue(다른 이용권명 "하프권") — 위치 고정 스코프 검증용. */
    private Venue venue2(Account owner) {
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(20000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(20000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueTicket ticket = VenueTicket.builder().name("하프권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday); ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("딥스테이션").type(VenueType.DEEP_POOL)
                .address("경기 용인").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    /** 2 정규회차 코스 — 각 회차가 두 venue(A·B) 후보를 모두 제공(위치 고정 스코프 검증). */
    private Course twoVenueCourse(Account ins, String refA, String tA, String refB, String tB) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(twoVenueRound(1, refA, tA, refB, tB));
        course.addRound(twoVenueRound(2, refA, tA, refB, tB));
        return courseRepo.save(course);
    }

    private CourseRound twoVenueRound(int idx, String refA, String tA, String refB, String tB) {
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(idx).build();
        RoundVenue a = RoundVenue.builder().venueRefId(refA).sortOrder(0).build();
        a.addTicket(RoundVenueTicket.builder().ticketRef(tA).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        RoundVenue b = RoundVenue.builder().venueRefId(refB).sortOrder(1).build();
        b.addTicket(RoundVenueTicket.builder().ticketRef(tB).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(a); round.addVenue(b);
        return round;
    }

    /** 2 정규회차 코스 — 회차마다 같은 venue·ticket 후보를 2번 등록(교집합 중복 재현 → dedup 검증). */
    private Course dupVenueCourse(Account ins, String venueRef, String ticketRef) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(dupVenueRound(1, venueRef, ticketRef));
        course.addRound(dupVenueRound(2, venueRef, ticketRef));
        return courseRepo.save(course);
    }

    private CourseRound dupVenueRound(int idx, String venueRef, String ticketRef) {
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(idx).build();
        for (int i = 0; i < 2; i++) { // 같은 (venue,ticket)을 두 번 — 후보 중복
            RoundVenue rv = RoundVenue.builder().venueRefId(venueRef).sortOrder(i).build();
            rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
            round.addVenue(rv);
        }
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

    /** round1 신청(D1) → repo 로 CONFIRMED+done 박기(게이트=직전 done). 반환 = enrollmentId. */
    private Long enrollWithDoneRound1(Account stu, Course course, String venueRef, String ticketRef) throws Exception {
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", venueRef, "ticketRef", ticketRef,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
        r1.setStatus(EnrollmentStatus.CONFIRMED);
        r1.setDoneAt(OffsetDateTime.now(ZoneOffset.UTC));
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
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

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
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        // 2회차 신청(D2)
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        // 강사 일정변경요청 — D2 대신 d3 슬롯(같은 이용권·블록) 제안
        Map<String, Object> slot = Map.of("date", d3.toString(), "ticketRef", ticket,
                "blockStart", START.toString(), "blockEnd", END.toString());
        mockMvc.perform(post("/instructor/enrollments/{id}/propose-slots", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("slots", List.of(slot)))))
                .andExpect(status().isOk());

        // hub 에 RESCHEDULING + 제안 슬롯
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[1].status").value("RESCHEDULING"))
                .andExpect(jsonPath("$.courses[0].rounds[1].proposedSlots[0].date").value(d3.toString()));

        // 학생이 그 슬롯 선택 → 사전 수락 → PAYMENT_PENDING
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(slot)))
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
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        mockMvc.perform(post("/instructor/enrollments/{id}/reject", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"안돼요\"}"))
                .andExpect(status().isBadRequest()); // 거절은 1회차만
    }

    @Test
    @DisplayName("M5 강사가 회차 완료(complete)하면 done 되고 hub 에 DONE·다음 회차가 열린다")
    void instructorCompletesRound() throws Exception {
        Account ins = instructor("ins-m5@pd.com", "강사M5", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-m5@pd.com", "학생M5", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", ticket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
        r1.setStatus(EnrollmentStatus.CONFIRMED); // 결제 흐름 대신 repo 로 확정
        roundRepo.save(r1);

        // 게이트: 아직 done 아니라 2회차 nextRoundIndex 없음
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.courses[0].nextRoundIndex").doesNotExist());

        // 강사 회차 완료
        mockMvc.perform(post("/instructor/enrollments/{id}/complete", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk());
        assertThat(roundRepo.findById(r1.getId()).orElseThrow().getDoneAt()).isNotNull();

        // done → hub DONE + 2회차 게이트 열림(아직 안 잡은 회차 남아 강의는 진행중)
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[0].status").value("DONE"))
                .andExpect(jsonPath("$.courses[0].status").value("PROGRESS"))
                .andExpect(jsonPath("$.courses[0].nextRoundIndex").value(2));
    }

    @Test
    @DisplayName("M6 학생 직접 일정수정(reschedule) — 회차 유지·옛 슬롯 이력 남고, 제안 외 슬롯이라 PENDING(강사 재수락)")
    void studentReschedulesOwnSlot() throws Exception {
        Account ins = instructor("ins-m6@pd.com", "강사M6", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, d3);
        Account stu = account("stu-m6@pd.com", "학생M6", Role.STUDENT);
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        // 2회차 신청(D2) → PENDING
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        // 직접 일정수정용 옵션 — 그 회차 슬롯 제공(1회차 옵션 shape)
        mockMvc.perform(get("/enrollments/rounds/{id}/options", r2.getId()).header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots").isArray());

        // 직접 d3 로 수정(강사 제안 외) → PENDING(재수락) + 옛 슬롯(D2) 이력
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.date").value(d3.toString()))
                .andExpect(jsonPath("$.slotHistory[0].date").value(D2.toString()));

        // slotHistory(LAZY)·HTTP 응답에서 D2 이력 확인 완료. 비-LAZY 컬럼만 DB 재확인.
        EnrollmentRound after = roundRepo.findById(r2.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(after.getDate()).isEqualTo(d3);
    }

    /* ─── PH* 강사 제안 보장 hold (hold-and-guarantee) ─── */

    /** 2회차 PENDING 회차를 만들어 반환(D2 슬롯). */
    private EnrollmentRound round2Pending(Account ins, Course course, String ref, String ticket, Account stu)
            throws Exception {
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        return roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
    }

    private ResultActions applyRound1(Account stu, Course course, String ref, String ticket, LocalDate date)
            throws Exception {
        return mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("courseId", course.getId(), "date", date.toString(),
                        "venueRefId", ref, "ticketRef", ticket,
                        "blockStart", START.toString(), "blockEnd", END.toString()))));
    }

    private ResultActions propose(Account ins, Long roundId, List<Map<String, Object>> slots) throws Exception {
        return mockMvc.perform(post("/instructor/enrollments/{id}/propose-slots", roundId)
                .header(HttpHeaders.AUTHORIZATION, token(ins))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("slots", slots))));
    }

    private Map<String, Object> slot(LocalDate date, String ticket) {
        return Map.of("date", date.toString(), "ticketRef", ticket,
                "blockStart", START.toString(), "blockEnd", END.toString());
    }

    @Test
    @DisplayName("PH1 강사가 제안하면 그 슬롯에 보장 좌석 hold 가 잡혀 다른 학생의 같은 슬롯 신청이 막힌다(만석 400)")
    void proposeHoldsSeatBlockingOthers() throws Exception {
        Account ins = instructor("ins-ph1@pd.com", "강사PH1", 1); // 정원 1 — hold 하나로 만석
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-ph1@pd.com", "학생PH1", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1); // 보장 hold 1개

        // 다른 학생이 같은 d3 슬롯 신청 → 보장 hold 가 유일 좌석을 잡아 만석(400)
        Account other = account("stu-ph1b@pd.com", "학생PH1B", Role.STUDENT);
        applyRound1(other, course, ref, ticket, d3).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PH2 학생이 제안 슬롯을 고르면 보장대로 성공(PAYMENT_PENDING), 안 고른 제안 슬롯 hold 는 풀려 다른 학생이 신청 가능")
    void pickGuaranteedAndReleasesOtherHolds() throws Exception {
        Account ins = instructor("ins-ph2@pd.com", "강사PH2", 1);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        LocalDate d4 = LocalDate.now().plusWeeks(4);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3); openCoverage(ins, d4);
        Account stu = account("stu-ph2@pd.com", "학생PH2", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket), slot(d4, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(2);

        // d3 선택 → 보장대로 성공
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(slot(d3, ticket))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.date").value(d3.toString()));
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).isEmpty(); // 모든 제안 hold 회수됨

        // 안 고른 d4 의 hold 가 풀려 다른 학생이 d4 를 신청할 수 있다
        Account other = account("stu-ph2b@pd.com", "학생PH2B", Role.STUDENT);
        applyRound1(other, course, ref, ticket, d4).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PH3 제안 슬롯은 최대 3개 — 4개를 보내면 거부된다(400)")
    void proposeRejectsMoreThanThree() throws Exception {
        Account ins = instructor("ins-ph3@pd.com", "강사PH3", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-ph3@pd.com", "학생PH3", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        List<Map<String, Object>> four = List.of(
                slot(LocalDate.now().plusWeeks(3), ticket), slot(LocalDate.now().plusWeeks(4), ticket),
                slot(LocalDate.now().plusWeeks(5), ticket), slot(LocalDate.now().plusWeeks(6), ticket));
        propose(ins, r2.getId(), four).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PH4 강사 제안 옵션 — remaining/full 포함 슬롯을 내려준다(내 코스 회차만, 남의 회차는 존재 숨김)")
    void instructorProposeOptions() throws Exception {
        Account ins = instructor("ins-ph4@pd.com", "강사PH4", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-ph4@pd.com", "학생PH4", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        mockMvc.perform(get("/instructor/enrollments/{id}/propose-options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots").isArray())
                .andExpect(jsonPath("$.slots[0].capacity").value(4))
                .andExpect(jsonPath("$.slots[0].remaining").exists());

        // 남의 회차 — 다른 강사가 보면 존재 숨김(ResourceNotFound → 이 레포는 400 매핑)
        Account ins2 = instructor("ins-ph4b@pd.com", "강사PH4B", 4);
        mockMvc.perform(get("/instructor/enrollments/{id}/propose-options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PH5 제안 만료(proposalTtl 경과) — 보장 hold 가 풀리고 제안이 사라지며 회차는 PENDING 유지, 다른 학생 신청 가능")
    void proposalExpirySweepReleasesHold() throws Exception {
        Account ins = instructor("ins-ph5@pd.com", "강사PH5", 1);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-ph5@pd.com", "학생PH5", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1);

        // proposalTtlHours(테스트 6h) 경과 — sweep
        int lapsed = expiryService.sweepExpiredProposals(OffsetDateTime.now(ZoneOffset.UTC).plusHours(7));
        assertThat(lapsed).isEqualTo(1);
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).isEmpty(); // 보장 hold 해제
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.PENDING); // 회차는 유지(취소 아님)

        // 제안만 lapse — hub 에서 RESCHEDULING 이 아니라 WAITING(제안 없는 PENDING)으로 보인다(proposedSlots 비움 확인)
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[1].status").value("WAITING"));

        // hold 풀려 다른 학생이 d3 신청 가능
        Account other = account("stu-ph5b@pd.com", "학생PH5B", Role.STUDENT);
        applyRound1(other, course, ref, ticket, d3).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PH6 강사 제안 옵션 — 슬롯에 이용권 표시명(ticketName)이 담긴다('일반권')")
    void instructorProposeOptionsCarriesTicketName() throws Exception {
        Account ins = instructor("ins-ph6@pd.com", "강사PH6", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-ph6@pd.com", "학생PH6", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        mockMvc.perform(get("/instructor/enrollments/{id}/propose-options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].ticketRef").value(ticket))
                .andExpect(jsonPath("$.slots[0].ticketName").value("일반권"));
    }

    @Test
    @DisplayName("PH7 강사 제안 옵션 — 위치 고정: 회차가 잡은 venue 슬롯만 내려준다(다른 후보 위치는 제외)")
    void instructorProposeOptionsScopedToBookedVenue() throws Exception {
        Account ins = instructor("ins-ph7@pd.com", "강사PH7", 4);
        Venue a = venue(ins);   // 잠실(일반권)
        Venue b = venue2(ins);  // 딥스테이션(하프권)
        String refA = VenueScope.token(VenueScope.CUSTOM, String.valueOf(a.getId()));
        String refB = VenueScope.token(VenueScope.CUSTOM, String.valueOf(b.getId()));
        String tA = a.getTickets().get(0).getRef();
        String tB = b.getTickets().get(0).getRef();
        Course course = twoVenueCourse(ins, refA, tA, refB, tB);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-ph7@pd.com", "학생PH7", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, refA, tA, stu); // venue A 로 예약

        mockMvc.perform(get("/instructor/enrollments/{id}/propose-options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots").isNotEmpty())
                .andExpect(jsonPath("$.slots[0].venueRefId").value(refA))
                .andExpect(jsonPath("$.slots[?(@.venueRefId == '" + refB + "')]").isEmpty()); // 다른 후보 위치 제외
    }

    @Test
    @DisplayName("PH8 강사 제안 옵션 — 같은 (날짜,위치,이용권,블록) 슬롯은 중복 없이 한 번만(후보 중복 방어)")
    void instructorProposeOptionsDeduplicatesSlots() throws Exception {
        Account ins = instructor("ins-ph8@pd.com", "강사PH8", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = dupVenueCourse(ins, ref, ticket); // 회차마다 같은 후보 2번
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-ph8@pd.com", "학생PH8", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        // 후보가 2배여도 (D1,D2)×1블록×1이용권 = 2슬롯 (중복 제거)
        mockMvc.perform(get("/instructor/enrollments/{id}/propose-options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(2));
    }

    @Test
    @DisplayName("PH9 학생 직접 일정수정 옵션 — 회차의 모든 후보 위치를 보여준다(위치 고정 아님 — 강사 제안과 대비)")
    void studentRoundOptionsOffersAllCandidateVenues() throws Exception {
        Account ins = instructor("ins-ph9@pd.com", "강사PH9", 4);
        Venue a = venue(ins);   // 잠실(일반권) — 예약한 위치
        Venue b = venue2(ins);  // 딥스테이션(하프권) — 다른 후보 위치
        String refA = VenueScope.token(VenueScope.CUSTOM, String.valueOf(a.getId()));
        String refB = VenueScope.token(VenueScope.CUSTOM, String.valueOf(b.getId()));
        String tA = a.getTickets().get(0).getRef();
        String tB = b.getTickets().get(0).getRef();
        Course course = twoVenueCourse(ins, refA, tA, refB, tB);
        // D1=round1·D2=round2 는 강사가 A 에 이미 같은 시간 일정 → 그 날 B 는 시간겹침(TIME_CONFLICT 표기, 필터 아님).
        // D3 는 일정 없는 날 — B 도 선택 가능. 둘 다 보여 "위치 자유"를 입증.
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-ph9@pd.com", "학생PH9", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, refA, tA, stu); // venue A 로 예약

        // 학생이 직접 일정수정 시 — 예약한 A 뿐 아니라 다른 후보 위치 B 도 자유 선택지로 내려온다
        mockMvc.perform(get("/enrollments/rounds/{roundId}/options", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[?(@.venueRefId == '" + refA + "')]").isNotEmpty())
                .andExpect(jsonPath("$.slots[?(@.venueRefId == '" + refB + "')]").isNotEmpty()) // 위치 고정 아님
                // 겹치는 날(D1)의 B 슬롯은 사라지지 않고 TIME_CONFLICT 로 표기
                .andExpect(jsonPath("$.slots[?(@.venueRefId == '" + refB + "' && @.date == '" + D1 + "')].unavailableReason")
                        .value(hasItem("TIME_CONFLICT")))
                // 일정 없는 날(D3)의 B 슬롯은 선택 가능(겹침 아님)
                .andExpect(jsonPath("$.slots[?(@.venueRefId == '" + refB + "' && @.date == '" + d3
                        + "' && @.unavailableReason == 'TIME_CONFLICT')]").isEmpty());
    }
}
