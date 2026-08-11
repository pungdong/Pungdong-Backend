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
    private static final LocalTime NIGHT_START = LocalTime.of(18, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(21, 0);

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
    @Autowired com.diving.pungdong.payment.PaymentOrderJpaRepo orderRepo;
    @Autowired com.diving.pungdong.payment.PaymentService paymentService;
    @Autowired com.diving.pungdong.payment.RefundOrderJpaRepo refundRepo;

    // PG 는 유일한 외부 경계라 mock(결정적). 레지스트리째 mock 하는 이유 = 어댑터 3개가 모두 빈이라
    // PaymentGateway 타입으로 mock 하면 주입이 모호해진다(PaymentUseCaseTest 와 같은 패턴).
    @org.springframework.boot.test.mock.mockito.MockBean com.diving.pungdong.payment.PaymentGatewayRegistry gateways;
    final com.diving.pungdong.payment.PaymentGateway gateway =
            org.mockito.Mockito.mock(com.diving.pungdong.payment.PaymentGateway.class);

    @org.junit.jupiter.api.BeforeEach
    void stubGatewayApproved() {
        org.mockito.BDDMockito.given(gateways.active()).willReturn(gateway);
        org.mockito.BDDMockito.given(gateways.forOrder(org.mockito.ArgumentMatchers.any())).willReturn(gateway);
        org.mockito.BDDMockito.given(gateway.provider()).willReturn(com.diving.pungdong.payment.PaymentProvider.STUB);
        org.mockito.BDDMockito.given(gateway.initParams(org.mockito.ArgumentMatchers.any()))
                .willReturn(Map.of("customerKey", "cust"));
        org.mockito.BDDMockito.given(gateway.confirm(org.mockito.ArgumentMatchers.any()))
                .willReturn(new com.diving.pungdong.payment.PaymentGateway.ConfirmResult(
                        true, "DONE", "간편결제", OffsetDateTime.now(ZoneOffset.UTC), null, "pk_test_1"));
    }

    @AfterEach
    void clean() {
        holdRepo.deleteAll();
        refundRepo.deleteAll(); // payment_order FK — 주문 삭제 전
        orderRepo.deleteAll(); // enrollment_round FK — 회차 삭제 전
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

    /** 같은 venue 안에 <b>가격이 다른 이용권 두 개</b> — 일반권(15,000·14~17) / 야간권(25,000·18~21). 차액 결제 검증용. */
    private Venue venueWithNightTicket(Account owner) {
        VenueDaypart dayWeekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        dayWeekday.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueDaypart dayWeekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        dayWeekend.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueTicket normal = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        normal.addDaypart(dayWeekday); normal.addDaypart(dayWeekend);

        VenueDaypart nightWeekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(25000).timeMode(TimeMode.FIXED).build();
        nightWeekday.addTimeBlock(VenueTimeBlock.builder().startTime(NIGHT_START).endTime(NIGHT_END).sortOrder(0).build());
        VenueDaypart nightWeekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(25000).timeMode(TimeMode.FIXED).build();
        nightWeekend.addTimeBlock(VenueTimeBlock.builder().startTime(NIGHT_START).endTime(NIGHT_END).sortOrder(0).build());
        VenueTicket night = VenueTicket.builder().name("야간권").sortOrder(1)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        night.addDaypart(nightWeekday); night.addDaypart(nightWeekend);

        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(normal); venue.addTicket(night);
        return venueRepo.save(venue);
    }

    /** 2 정규회차 코스 — 각 회차가 같은 venue 의 <b>두 이용권</b>을 모두 후보로 제공(차액 결제 검증용). */
    private Course twoTicketCourse(Account ins, String venueRef, String normalTicket, String nightTicket) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        for (int idx = 1; idx <= 2; idx++) {
            CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(idx).build();
            RoundVenue rv = RoundVenue.builder().venueRefId(venueRef).sortOrder(0).build();
            rv.addTicket(RoundVenueTicket.builder().ticketRef(normalTicket).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
            rv.addTicket(RoundVenueTicket.builder().ticketRef(nightTicket).daypart(DaypartKind.WEEKDAY).sortOrder(1).build());
            round.addVenue(rv);
            course.addRound(round);
        }
        return courseRepo.save(course);
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

    /** 야간 블록까지 덮는 예약가능시간(09~22) — 차액 결제(야간권) 검증용. */
    private void openCoverageIncludingNight(Account ins, LocalDate date) {
        coverageRepo.save(AvailabilityCoverage.builder().instructor(ins).date(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(22, 0)).build());
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
    @DisplayName("M7 2회차도 신청 즉시 결제 — 결제하면 ACCEPT_PENDING(강사 확인 대기), 강사가 수락하면 CONFIRMED")
    void secondRoundPrepayThenInstructorAccepts() throws Exception {
        Account ins = instructor("ins-m7@pd.com", "강사M7", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-m7@pd.com", "학생M7", Role.STUDENT);
        EnrollmentRound r2 = round2Pending(ins, course, ref, ticket, stu);

        // 권위 금액 = 부대비용만(수강료는 1회차 주문에 전액) = 입장료 15,000
        String prepared = mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enrollmentId", r2.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(15000))
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(prepared).path("orderId").asText();

        // 결제 → 1회차와 똑같이 ACCEPT_PENDING(옛 흐름의 "곧장 CONFIRMED" 아님)
        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"),
                                "orderId", orderId, "amount", 15000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentEnrollmentStatus").value("ACCEPT_PENDING"));

        // 강사 수락 → 확정 (통일 전에는 이 호출이 400 이었다 — 2회차가 PENDING 으로 들어와 accept 게이트에 막혔음)
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    /* ─── SP* 미결제 재신청(supersede) — 옛 좌석이 남지 않게 ─── */

    @Test
    @DisplayName("SP1 결제 전 다른 날짜로 다시 신청하면 수강이 하나로 유지되고 옛 좌석은 반납된다(이중 점유 없음)")
    void reapplyBeforePaymentSupersedes() throws Exception {
        Account ins = instructor("ins-sp1@pd.com", "강사SP1", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-sp1@pd.com", "학생SP1", Role.STUDENT);

        applyRound1(stu, course, ref, ticket, D1).andExpect(status().isCreated());
        // 결제 화면에서 뒤로 → 날짜 바꿔 다시 신청
        applyRound1(stu, course, ref, ticket, D2).andExpect(status().isCreated());

        // 수강도 회차도 하나뿐 — 새로 쌓이지 않는다
        assertThat(enrollmentRepo.findByStudentIdOrderByIdDesc(stu.getId())).hasSize(1);
        List<EnrollmentRound> rounds = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId());
        assertThat(rounds).hasSize(1);
        assertThat(rounds.get(0).getDate()).isEqualTo(D2);
        assertThat(rounds.get(0).getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        // 옛 슬롯(D1) 일정은 점유 0 이 되어 정리됐다 = 좌석 반납
        assertThat(sessionRepo.findAll()).hasSize(1);
        assertThat(sessionRepo.findAll().get(0).getDate()).isEqualTo(D2);
    }

    @Test
    @DisplayName("SP2 내 유령 미결제 점유가 나를 막지 않는다 — 겹치는 시간대로 재신청해도 이중부킹(-1015) 안 남")
    void reapplyToOverlappingSlotIsNotBlockedByOwnGhost() throws Exception {
        Account ins = instructor("ins-sp2@pd.com", "강사SP2", 4);
        Venue v = venueWithNightTicket(ins); // 일반권 14~17 / 야간권 18~21
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, v.getTickets().get(1).getRef());
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-sp2@pd.com", "학생SP2", Role.STUDENT);

        applyRound1(stu, course, ref, dayTicket, D1).andExpect(status().isCreated());
        // 같은 날 같은 이용권으로 다시 신청(= 완전히 같은 슬롯) — 내 점유가 만석/겹침으로 나를 막으면 안 된다
        applyRound1(stu, course, ref, dayTicket, D1).andExpect(status().isCreated());

        assertThat(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId())).hasSize(1);
        assertThat(sessionRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("SP3 2회차도 결제 전이면 다시 신청해 날짜를 바꿀 수 있다(예전엔 400 으로 갇혔다)")
    void reapplySecondRoundBeforePayment() throws Exception {
        Account ins = instructor("ins-sp3@pd.com", "강사SP3", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-sp3@pd.com", "학생SP3", Role.STUDENT);
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        // 결제 전에 날짜 변경 — 취소하지 않고 바로 다시 신청
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roundIndex").value(2))
                .andExpect(jsonPath("$.date").value(d3.toString()));

        // 2회차는 여전히 하나 — 중복 안 쌓임
        long round2Count = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).stream()
                .filter(r -> Integer.valueOf(2).equals(r.getRoundIndex())).count();
        assertThat(round2Count).isEqualTo(1);
    }

    @Test
    @DisplayName("SP4 결제완료(ACCEPT_PENDING) 회차는 supersede 대상이 아니다 — 강사 결정 대기 건을 건드리지 않는다")
    void paidRoundIsNotSuperseded() throws Exception {
        Account ins = instructor("ins-sp4@pd.com", "강사SP4", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-sp4@pd.com", "학생SP4", Role.STUDENT);

        applyRound1(stu, course, ref, ticket, D1).andExpect(status().isCreated());
        EnrollmentRound paid = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 결제완료 건이 있는데 또 신청 → supersede 하지 않고 별도 수강으로 간다(강사 결정 대기 건 보호)
        applyRound1(stu, course, ref, ticket, D2).andExpect(status().isCreated());

        EnrollmentRound after = roundRepo.findById(paid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING); // 그대로
        assertThat(after.getDate()).isEqualTo(D1);                                // 슬롯도 그대로
        assertThat(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId())).hasSize(2);
    }

    @Test
    @DisplayName("M15 더 비싼 시간대로 옮기려면 차액만 결제 — 슬롯이 바뀌고 강사 재수락 대기로 돌아간다")
    void slotChangeDiffPayment() throws Exception {
        Account ins = instructor("ins-m15@pd.com", "강사M15", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();   // 일반권 15,000 (14~17)
        String nightTicket = v.getTickets().get(1).getRef(); // 야간권 25,000 (18~21)
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1); openCoverageIncludingNight(ins, D2);
        Account stu = account("stu-m15@pd.com", "학생M15", Role.STUDENT);

        // 1회차를 일반권(입장료 15,000)으로 신청 후 결제완료 상태로
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 야간권(25,000)으로 옮기기 — 차액 10,000 만 청구된다
        String prepared = mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000)) // 25,000 − 15,000
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(prepared).path("orderId").asText();
        // 결제창이 떠 있는 동안 목표 슬롯 자리를 잡아둔다(돈만 받고 자리는 못 주는 상태 방지)
        assertThat(holdRepo.findAll()).hasSize(1);

        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"),
                                "orderId", orderId, "amount", 10000))))
                .andExpect(status().isOk());

        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getTicketRef()).isEqualTo(nightTicket);
        assertThat(after.getBlockStart()).isEqualTo(NIGHT_START);
        assertThat(after.getEntrySnapshot()).isEqualTo(25000);
        // ★ 학생이 임의로 고른 시간이라 강사 동의가 없다 → 강사 결정 대기로 되돌아간다(확정 아님)
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
        assertThat(holdRepo.findAll()).isEmpty(); // hold 는 실점유로 전환되며 해제

        // 강사 hub 엔 "변경 검토(CHANGING)" 로 뜬다 — 옛 슬롯이 이력에 남았으므로
        mockMvc.perform(get("/instructor/enrollments/hub").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollments[0].rounds[0].status").value("CHANGING"));

        // 강사가 수락해야 비로소 확정
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("C1 더 비싼 슬롯으로 그냥 옮기려 하면 전용 코드(-1018)로 거부 — 다른 400 과 구분돼 차액 결제로 유도된다")
    void rescheduleToPricierSlotReturnsDedicatedCode() throws Exception {
        Account ins = instructor("ins-c1@pd.com", "강사C1", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();   // 일반권 15,000 (14~17)
        String nightTicket = v.getTickets().get(1).getRef(); // 야간권 25,000 (18~21)
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-c1@pd.com", "학생C1", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 결제 없이 야간권(25,000)으로 reschedule → 금액이 오르므로 거부. 범용 -1011 이 아니라 -1018.
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("date", D1.toString(), "venueRefId", ref,
                                "ticketRef", nightTicket,
                                "blockStart", NIGHT_START.toString(), "blockEnd", NIGHT_END.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1018));

        // 슬롯은 그대로 — 거부는 롤백된다
        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getTicketRef()).isEqualTo(dayTicket);
        assertThat(after.getEntrySnapshot()).isEqualTo(15000);
    }

    @Test
    @DisplayName("C1-1 강사가 더 비싼 슬롯을 제안했고 학생이 그걸 고르면 pick-slot 도 -1018 — 제안·슬롯은 그대로 롤백된다")
    void pickingPricierProposedSlotRequiresAdditionalPayment() throws Exception {
        Account ins = instructor("ins-c11@pd.com", "강사C11", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();   // 일반권 15,000 (14~17)
        String nightTicket = v.getTickets().get(1).getRef(); // 야간권 25,000 (18~21)
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1); openCoverageIncludingNight(ins, D2);
        Account stu = account("stu-c11@pd.com", "학생C11", Role.STUDENT);

        // 일반권(15,000)으로 신청 → 결제완료(강사 결정 대기)
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 강사가 더 비싼 야간 슬롯을 제안 — 이건 허용된다(2026-08-10)
        propose(ins, r1.getId(), List.of(slot(D2, nightTicket, NIGHT_START, NIGHT_END)))
                .andExpect(status().isOk());

        // 학생이 그 제안을 고르면 결제 없이는 못 간다 — 범용 -1011 이 아니라 -1018 로 차액 결제를 가리킨다
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(slot(D2, nightTicket, NIGHT_START, NIGHT_END))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1018));

        // 롤백 확인 — pick-slot 은 던지기 전에 회차를 이미 고쳐놓으므로 트랜잭션이 되돌려야 한다.
        // 제안 목록은 LAZY 라 세션 밖에서 못 읽는다 → HTTP(일정 hub)로 확인한다.
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds[0].status").value("RESCHEDULING"))
                .andExpect(jsonPath("$.courses[0].rounds[0].proposedSlots.length()").value(1)) // 제안 유지 — 다시 고를 수 있다
                .andExpect(jsonPath("$.courses[0].rounds[0].date").value(D1.toString()));      // 슬롯도 원래대로

        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
        assertThat(after.getTicketRef()).isEqualTo(dayTicket);
        assertThat(after.getEntrySnapshot()).isEqualTo(15000);
        assertThat(holdRepo.findByProposalRoundId(r1.getId())).hasSize(1); // 보장 hold 도 유지
    }

    @Test
    @DisplayName("C1-2 정원 1에서도 제안→(-1018)→차액 결제가 이어진다 — 자기 제안 hold 에 자기가 막히지 않는다")
    void pickSlotDiffPaymentNotBlockedByOwnProposalHold() throws Exception {
        Account ins = instructor("ins-c12@pd.com", "강사C12", 1); // 정원 1 — hold 하나로 만석
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();   // 일반권 15,000
        String nightTicket = v.getTickets().get(1).getRef(); // 야간권 25,000
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1); openCoverageIncludingNight(ins, D2);
        Account stu = account("stu-c12@pd.com", "학생C12", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 강사가 더 비싼 야간 슬롯 제안 → 그 자리에 보장 hold 가 잡힌다(정원 1이라 그것만으로 만석)
        propose(ins, r1.getId(), List.of(slot(D2, nightTicket, NIGHT_START, NIGHT_END)))
                .andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r1.getId())).hasSize(1);

        // 고르면 -1018 (차액 결제로 가라)
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(slot(D2, nightTicket, NIGHT_START, NIGHT_END))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1018));

        // ★ 안내대로 차액 결제로 갔을 때 — 그 자리를 붙들고 있는 건 "나를 위한" 제안 hold 다.
        //   그걸 만석으로 세면 학생은 안내받은 경로에서 데드엔드에 빠진다.
        String prepared = mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D2.toString(), "targetTicketRef", nightTicket,
                                "targetVenueRefId", ref,
                                "targetBlockStart", NIGHT_START.toString(), "targetBlockEnd", NIGHT_END.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000))
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(prepared).path("orderId").asText();

        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_c12"),
                                "orderId", orderId, "amount", 10000))))
                .andExpect(status().isOk());

        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getTicketRef()).isEqualTo(nightTicket);
        assertThat(after.getDate()).isEqualTo(D2);
        // 옛 제안 hold 가 남아 그 자리를 이중으로 묶으면 안 된다(정원 1이라 곧 남 신청도 막힌다)
        assertThat(holdRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("C1-3 정원 1에서 제안받은 자리로 (pick-slot 대신) reschedule 해도 내 제안 hold 에 막히지 않는다")
    void rescheduleIntoProposedSlotNotBlockedByOwnHold() throws Exception {
        Account ins = instructor("ins-c13@pd.com", "강사C13", 1); // 정원 1 — hold 하나로 만석
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-c13@pd.com", "학생C13", Role.STUDENT);
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1);

        // 같은 자리를 reschedule 로 보낸다(입장료 동일 → 차액 없음). 그 자리를 붙든 건 "나를 위한" hold 다.
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(d3.toString()));

        assertThat(holdRepo.findAll()).isEmpty(); // 실점유로 전환되며 hold 는 남지 않는다
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getDate()).isEqualTo(d3);
    }

    @Test
    @DisplayName("C2 차액 결제 준비는 슬롯이 준 시간 표기(\"18:00:00\")를 그대로 받는다 — 자를 필요 없다")
    void prepareAcceptsFullSecondsTimeFormat() throws Exception {
        Account ins = instructor("ins-c2@pd.com", "강사C2", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        String nightTicket = v.getTickets().get(1).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-c2@pd.com", "학생C2", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // "18:00:00" — EnrollmentOptionsResponse.Slot.blockStart 가 내려주는 그 표기
        mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00:00", "targetBlockEnd", "21:00:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000));
    }

    @Test
    @DisplayName("C3 차액 결제 승인 응답엔 scheduleChange=true 가 실린다 — 완료 화면이 일반 결제와 문구를 가른다")
    void confirmMarksScheduleChange() throws Exception {
        Account ins = instructor("ins-c3@pd.com", "강사C3", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        String nightTicket = v.getTickets().get(1).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-c3@pd.com", "학생C3", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        String prepared = mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(prepared).path("orderId").asText();

        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_c3"),
                                "orderId", orderId, "amount", 10000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleChange").value(true));

        // 주문 재조회(이니시스 성공화면 경로)에서도 같은 플래그가 온다 — 쿠키 우회 불필요
        mockMvc.perform(get("/payments/orders/{orderId}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleChange").value(true));
    }

    @Test
    @DisplayName("C4 위치까지 바꾸면서 비싸지면 -1018(차액 결제 유도)이 아니라 -1019 — 차액 경로로는 못 가는 조합이다")
    void venueChangeWithPriceIncreaseIsRejectedSeparately() throws Exception {
        Account ins = instructor("ins-c4@pd.com", "강사C4", 4);
        Venue cheap = venue(ins);                                   // 일반 위치(입장료 15,000)
        Venue pricey = venueWithNightTicket(ins);                   // 야간권 25,000 보유 위치
        String cheapRef = VenueScope.token(VenueScope.CUSTOM, String.valueOf(cheap.getId()));
        String priceyRef = VenueScope.token(VenueScope.CUSTOM, String.valueOf(pricey.getId()));
        String cheapTicket = cheap.getTickets().get(0).getRef();
        String nightTicket = pricey.getTickets().get(1).getRef();   // 25,000
        Course course = twoVenueCourse(ins, cheapRef, cheapTicket, priceyRef, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-c4@pd.com", "학생C4", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", cheapRef, "ticketRef", cheapTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 위치 B + 더 비싼 야간 슬롯으로 변경 시도 → -1018 이면 FE 가 차액 결제로 유도하고,
        // 그 경로는 위치를 못 바꿔 학생이 고른 적 없는 위치 A 로 옮겨진다. 그래서 -1019 로 갈라 거부한다.
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("date", D1.toString(), "venueRefId", priceyRef,
                                "ticketRef", nightTicket,
                                "blockStart", NIGHT_START.toString(), "blockEnd", NIGHT_END.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1019));

        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getVenueRefId()).isEqualTo(cheapRef); // 롤백 — 위치 그대로
        assertThat(after.getTicketRef()).isEqualTo(cheapTicket);
    }

    @Test
    @DisplayName("C5 차액 결제 준비에 다른 위치를 실어 보내면 -1019 — 결제창이 열리기 전에 막는다(2차 방어)")
    void prepareRejectsMismatchedTargetVenue() throws Exception {
        Account ins = instructor("ins-c5@pd.com", "강사C5", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        String nightTicket = v.getTickets().get(1).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-c5@pd.com", "학생C5", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 회차의 현재 위치가 아닌 값을 실어 보냄 → 주문도 hold 도 만들기 전에 거부
        mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00",
                                "targetVenueRefId", "CUSTOM:999999"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1019));

        assertThat(holdRepo.findAll()).isEmpty(); // 좌석 hold 가 잡히지 않았다

        // 같은 위치를 실어 보내면 정상 통과 — 가드지 차단이 아니다
        mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00",
                                "targetVenueRefId", ref))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000));
    }

    @Test
    @DisplayName("M17 차액을 냈어도 강사가 그 시간은 안 된다고 거절하면 그 회차 전액(차액 포함) 환불된다")
    void instructorCanRejectAfterDiffPayment() throws Exception {
        Account ins = instructor("ins-m17@pd.com", "강사M17", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        String nightTicket = v.getTickets().get(1).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-m17@pd.com", "학생M17", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));
        // 원결제 주문(차액 환불이 이 주문까지 훑는지 보기 위해 실제 승인 주문을 심는다)
        orderRepo.save(com.diving.pungdong.payment.PaymentOrder.builder()
                .orderId("ord-m17-base").enrollmentRound(r1).amount(315000).orderName("원결제")
                .status(com.diving.pungdong.payment.PaymentStatus.DONE).paymentKey("pkBase")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        String prepared = mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(prepared).path("orderId").asText();
        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"),
                                "orderId", orderId, "amount", 10000))))
                .andExpect(status().isOk());

        // 강사: "그 시간은 다른 데 잡혀 있어요" → 거절
        mockMvc.perform(post("/instructor/enrollments/{id}/reject", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"그 시간은 어려워요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 그 회차의 승인 주문을 모두 환불 — 원결제도 차액도(회차 단위 집계, #203)
        org.mockito.Mockito.verify(gateway).cancel(org.mockito.ArgumentMatchers.eq("pkBase"),
                org.mockito.ArgumentMatchers.eq(315000), org.mockito.ArgumentMatchers.eq(315000),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(gateway).cancel(org.mockito.ArgumentMatchers.eq("pk_test_1"),
                org.mockito.ArgumentMatchers.eq(10000), org.mockito.ArgumentMatchers.eq(10000),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("M16 차액을 결제하지 않고 방치하면 주문만 만료되고 좌석은 반납된다 — 예약은 원래 슬롯 그대로")
    void slotChangeOrderExpiresWithoutTouchingEnrollment() throws Exception {
        Account ins = instructor("ins-m16@pd.com", "강사M16", 4);
        Venue v = venueWithNightTicket(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String dayTicket = v.getTickets().get(0).getRef();
        String nightTicket = v.getTickets().get(1).getRef();
        Course course = twoTicketCourse(ins, ref, dayTicket, nightTicket);
        openCoverageIncludingNight(ins, D1);
        Account stu = account("stu-m16@pd.com", "학생M16", Role.STUDENT);

        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", D1.toString(),
                                "venueRefId", ref, "ticketRef", dayTicket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
        EnrollmentRound r1 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        mockMvc.perform(post("/payments/prepare").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roundId", r1.getId(),
                                "targetDate", D1.toString(), "targetTicketRef", nightTicket,
                                "targetBlockStart", "18:00", "targetBlockEnd", "21:00"))))
                .andExpect(status().isOk());
        assertThat(holdRepo.findAll()).hasSize(1);

        // 결제창 window(paymentTtlHours=12h) 경과 — 스위프
        int expired = paymentService.sweepExpiredSlotChangeOrders(OffsetDateTime.now(ZoneOffset.UTC).plusHours(13));

        assertThat(expired).isEqualTo(1);
        assertThat(holdRepo.findAll()).isEmpty(); // 잡아둔 자리 반납
        EnrollmentRound after = roundRepo.findById(r1.getId()).orElseThrow();
        assertThat(after.getTicketRef()).isEqualTo(dayTicket);   // 예약은 원래 슬롯 그대로
        assertThat(after.getBlockStart()).isEqualTo(START);
        assertThat(after.getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING); // 롤백할 게 없다
    }

    @Test
    @DisplayName("M3 강사 일정변경요청 → 학생이 제안 날짜 선택하면 추가 결제 없이 곧장 CONFIRMED(강사가 승인한 자리)")
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
        EnrollmentRound r2 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        // 강사 일정변경요청 — D2 대신 d3 슬롯(같은 이용권·블록) 제안. 결제완료 건이라 강사 차례다.
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

        // 학생이 그 슬롯 선택("ㅇㅋ") → 이미 결제 + 강사가 승인한 자리 → 곧장 확정
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(slot)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.date").value(d3.toString()));
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("M4 2회차도 강사가 거절할 수 있고(그 회차만 무효), 학생은 그 회차를 다른 날짜로 다시 신청할 수 있다")
    void rejectSecondRoundThenReapply() throws Exception {
        Account ins = instructor("ins-m4@pd.com", "강사M4", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-m4@pd.com", "학생M4", Role.STUDENT);
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        mockMvc.perform(post("/instructor/enrollments/{id}/reject", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(ins))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"그날은 어려워요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.REJECTED);

        // 거절은 그 회차만 무효 — 수강은 살아 있고 2회차 자리가 비었으므로 다른 날짜(d3)로 다시 신청 가능
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roundIndex").value(2))
                .andExpect(jsonPath("$.date").value(d3.toString()));
    }

    @Test
    @DisplayName("M4-1 학생이 강사 제안을 다 거절(ㄴㄴ)해 취소해도, 나중에 그 회차를 다시 신청할 수 있다")
    void cancelAfterProposalThenReapply() throws Exception {
        Account ins = instructor("ins-m41@pd.com", "강사M41", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        LocalDate d4 = LocalDate.now().plusWeeks(4);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3); openCoverage(ins, d4);
        Account stu = account("stu-m41@pd.com", "학생M41", Role.STUDENT);
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);

        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        EnrollmentRound r2 = paid(roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0));

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());

        // 제안이 다 안 맞음 → 취소(ㄴㄴ). 결제분은 자동환불(주문 없으면 no-op) + 좌석 반납
        mockMvc.perform(post("/enrollments/{id}/cancel", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 나중에 고민해보고 다시 신청 — 그 회차 자리가 비었으므로 열려 있다
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roundIndex").value(2));
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

    @Test
    @DisplayName("M6-1 결제 후 학생 재제안 — 강사 제안이 다 안 맞으면 내 슬롯으로 되보낸다(결제 유지·강사에게 변경검토 CHANGING)")
    void studentCounterProposesAfterPayment() throws Exception {
        Account ins = instructor("ins-m61@pd.com", "강사M61", 4);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        LocalDate d4 = LocalDate.now().plusWeeks(4);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3); openCoverage(ins, d4);
        Account stu = account("stu-m61@pd.com", "학생M61", Role.STUDENT);
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1); // 제안이 d3 자리를 붙들고 있다

        // 제안(d3)이 안 맞아 학생이 d4 로 되보냄 — 결제는 유지되고 강사 결정 대기로 돌아간다
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, d4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPT_PENDING"))
                .andExpect(jsonPath("$.date").value(d4.toString()));

        // ★ 안 고른 제안의 보장 hold 도 함께 풀린다 — 안 풀면 아무도 못 쓰는 자리가 6h 잠긴다
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).isEmpty();

        // 강사 hub 에는 "변경 검토(CHANGING)" 로 뜬다(= 강사 액션 필요)
        mockMvc.perform(get("/instructor/enrollments/hub").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollments[0].flag").value("CHANGE_REQUEST"))
                .andExpect(jsonPath("$.enrollments[0].rounds[1].status").value("CHANGING"));
    }

    /* ─── PH* 강사 제안 보장 hold (hold-and-guarantee) ─── */

    /** 2회차를 만들어 <b>결제완료(ACCEPT_PENDING)</b> 로 올려 반환(D2 슬롯) — 강사 결정(수락/거절/제안)이 열리는 상태. */
    private EnrollmentRound round2Paid(Account ins, Course course, String ref, String ticket, Account stu)
            throws Exception {
        return paid(round2Pending(ins, course, ref, ticket, stu));
    }

    /** 2회차 PENDING(미결제) 회차를 만들어 반환(D2 슬롯). */
    private EnrollmentRound round2Pending(Account ins, Course course, String ref, String ticket, Account stu)
            throws Exception {
        Long enrollmentId = enrollWithDoneRound1(stu, course, ref, ticket);
        mockMvc.perform(post("/enrollments/{id}/rounds", enrollmentId).header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(roundBody(ref, ticket, D2)))
                .andExpect(status().isCreated());
        return roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
    }

    /**
     * 회차를 결제완료(ACCEPT_PENDING)로 올린다 — 결제 자체의 검증은 {@code PaymentUseCaseTest} 소관이라 여기선
     * 진행 mechanics 만 보려고 상태를 직접 세팅한다. respondedAt = 강사 24h 응답시계 시작(결제시각).
     */
    private EnrollmentRound paid(EnrollmentRound r) {
        r.setStatus(EnrollmentStatus.ACCEPT_PENDING);
        r.setRespondedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return roundRepo.save(r);
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

    /** 시간대까지 지정 — 야간권(더 비싼 daypart) 제안·선택용. */
    private Map<String, Object> slot(LocalDate date, String ticket, LocalTime start, LocalTime end) {
        return Map.of("date", date.toString(), "ticketRef", ticket,
                "blockStart", start.toString(), "blockEnd", end.toString());
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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1); // 보장 hold 1개

        // 다른 학생이 같은 d3 슬롯 신청 → 보장 hold 가 유일 좌석을 잡아 만석(400)
        Account other = account("stu-ph1b@pd.com", "학생PH1B", Role.STUDENT);
        applyRound1(other, course, ref, ticket, d3).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PH2 학생이 제안 슬롯을 고르면 보장대로 성공(CONFIRMED), 안 고른 제안 슬롯 hold 는 풀려 다른 학생이 신청 가능")
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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket), slot(d4, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(2);

        // d3 선택 → 보장대로 성공
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(slot(d3, ticket))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

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
    @DisplayName("PH5 제안 만료(proposalTtl 경과) — 보장 hold 가 풀리고 제안이 사라지며 회차는 유지(강사 차례로 복귀), 다른 학생 신청 가능")
    void proposalExpirySweepReleasesHold() throws Exception {
        Account ins = instructor("ins-ph5@pd.com", "강사PH5", 1);
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = twoRoundCourse(ins, ref, ticket);
        LocalDate d3 = LocalDate.now().plusWeeks(3);
        openCoverage(ins, D1); openCoverage(ins, D2); openCoverage(ins, d3);
        Account stu = account("stu-ph5@pd.com", "학생PH5", Role.STUDENT);
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

        propose(ins, r2.getId(), List.of(slot(d3, ticket))).andExpect(status().isOk());
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).hasSize(1);

        // proposalTtlHours(테스트 6h) 경과 — sweep
        int lapsed = expiryService.sweepExpiredProposals(OffsetDateTime.now(ZoneOffset.UTC).plusHours(7));
        assertThat(lapsed).isEqualTo(1);
        assertThat(holdRepo.findByProposalRoundId(r2.getId())).isEmpty(); // 보장 hold 해제
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.ACCEPT_PENDING); // 회차는 유지(취소 아님) — 강사가 다시 결정

        // 제안만 lapse — hub 에서 RESCHEDULING 이 아니라 WAITING(제안 없는 강사 확인 중)으로 보인다(proposedSlots 비움 확인)
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.courses[0].rounds[1].status").value("WAITING"));

        // 만료된 제안을 뒤늦게 고르면 전용 코드(-1020) — 사용자 잘못이 아니라 "직접 고르세요" 로 안내해야 하므로
        // 범용 -1011("보내신 요청 정보가 옳지 않습니다.")과 가른다.
        mockMvc.perform(post("/enrollments/rounds/{id}/pick-slot", r2.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON).content(json(slot(d3, ticket))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1020));

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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

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
        EnrollmentRound r2 = round2Paid(ins, course, ref, ticket, stu);

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
