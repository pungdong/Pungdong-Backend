package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentOrderJpaRepo;
import com.diving.pungdong.payment.PaymentStatus;
import com.diving.pungdong.payment.TossPaymentClient;
import com.diving.pungdong.venue.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제(payment) use-case — 실 H2 + Spring Security 필터 + 실 서비스/JPA. 외부 PG(토스)만 {@link TossPaymentClient}
 * {@code @MockBean} 으로 격리(결정적). 토스 결제위젯 v2 흐름의 "수락 → 결제 → 확정".
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. P* 결제 준비·승인 / 보안·멱등.
 *
 * <p>흐름: 학생 신청(PENDING) → 강사 수락(PAYMENT_PENDING) → {@code /payments/prepare}(서버 권위 금액·주문 생성)
 * → 위젯 결제 → {@code /payments/confirm}(금액 대조 후 토스 승인 → CONFIRMED). 권위 금액 = 코스 라이브 수강료
 * + 입장료 스냅샷 + 장비 스냅샷. 여기선 350,000 + 15,000 + 0 = 365,000(결정적). ⚠️ raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentUseCaseTest {

    private static final int EXPECTED_AMOUNT = 365000; // 수강료 350,000 + 입장료 15,000 + 장비 0

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired PaymentOrderJpaRepo orderRepo;

    @MockBean TossPaymentClient tossClient; // 외부 PG 경계만 mock

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalTime B_START = LocalTime.of(14, 0);
    private static final LocalTime B_END = LocalTime.of(17, 0);

    @BeforeEach
    void stubTossDone() {
        // 기본 — 토스 승인은 DONE(금액 무관). 호출 자체가 거절돼야 하는 시나리오는 verifyNoInteractions 로 확인.
        given(tossClient.confirm(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .willReturn(new TossPaymentClient.TossConfirmResult("DONE", "간편결제", OffsetDateTime.now(), null));
    }

    @AfterEach
    void cleanUp() {
        orderRepo.deleteAll();
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── P* 결제 ─── */

    @Test
    @DisplayName("P1 수락된 신청에 prepare 하면 서버 권위 금액으로 READY 주문이 생기고 위젯 구동값을 돌려준다")
    void prepareCreatesReadyOrder() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = accepted(stu, s);

        mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(EXPECTED_AMOUNT))
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.customerKey").value("cust-" + stu.getId()));

        PaymentOrder order = orderRepo.findByEnrollmentIdAndStatus(e.getId(), PaymentStatus.READY).orElseThrow();
        assertThat(order.getAmount()).isEqualTo(EXPECTED_AMOUNT);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("P2 confirm 성공 → 토스 승인 후 주문 DONE, 신청 CONFIRMED 로 확정된다")
    void confirmConfirmsEnrollment() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = accepted(stu, s);
        String orderId = prepareOrderId(stu, e);

        mockMvc.perform(post("/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("paymentKey", "pk_test_1", "orderId", orderId, "amount", EXPECTED_AMOUNT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.enrollmentStatus").value("CONFIRMED"));

        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(enrollmentRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("P3 confirm 의 금액이 서버 권위 금액과 다르면 400 — 토스 호출 안 하고 신청은 그대로")
    void confirmRejectsAmountMismatch() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = accepted(stu, s);
        String orderId = prepareOrderId(stu, e);

        mockMvc.perform(post("/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("paymentKey", "pk_test_1", "orderId", orderId, "amount", 1000))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tossClient); // 금액 대조에서 막혀 PG 미호출
        assertThat(enrollmentRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.PAYMENT_PENDING);
        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("P4 같은 주문을 confirm 두 번 해도 멱등 — 둘째도 200 DONE(이중 승인 없음)")
    void confirmIsIdempotent() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = accepted(stu, s);
        String orderId = prepareOrderId(stu, e);
        String body = json(Map.of("paymentKey", "pk_test_1", "orderId", orderId, "amount", EXPECTED_AMOUNT));

        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.enrollmentStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("P5 아직 수락 전(PENDING) 신청에 prepare 하면 400(결제 대기 상태가 아님)")
    void prepareRejectsNonPaymentPending() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = submitOk(stu, s); // PENDING — 강사 수락 전

        mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P6 남의 신청에 prepare 하면 400(존재 숨김 — repo 컨벤션 '비소유=400')")
    void prepareHidesOthers() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        Enrollment e = accepted(stu, s);
        Account other = account("other@pd.com", "남");

        mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P7 정원 1 인 슬롯에 둘이 신청해도 수락은 한 명만 — 결제대기 점유가 둘째 수락을 막는다(400)")
    void acceptBlockedByPaymentPendingOccupancy() throws Exception {
        Object[] s = setup(1); // 정원 1
        Account ins = (Account) s[4];
        Enrollment first = submitOk(account("p7a@pd.com", "학생7A"), s);
        Enrollment second = submitOk(account("p7b@pd.com", "학생7B"), s); // PENDING 은 캡 안 함 → 둘 다 신청 OK

        mockMvc.perform(post("/instructor/enrollments/{id}/accept", first.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", second.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isBadRequest()); // 결제대기 1명이 정원 1 을 채움
    }

    /* ─── helpers ─── */

    /** 신청 → 강사 수락(PAYMENT_PENDING)까지 진행한 enrollment 반환. */
    private Enrollment accepted(Account student, Object[] s) throws Exception {
        Enrollment e = submitOk(student, s);
        Account ins = (Account) s[4];
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk());
        return enrollmentRepo.findById(e.getId()).orElseThrow();
    }

    private String prepareOrderId(Account student, Enrollment e) throws Exception {
        String resp = mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("orderId").asText();
    }

    private Enrollment submitOk(Account student, Object[] s) throws Exception {
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        String ticketRef = ticketRefOf((Venue) s[1]);
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRef))))
                .andExpect(status().isCreated());
        return enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId()).get(0);
    }

    /** 강사·venue·course 한 세트 + coverage 09–18. 인덱스 [course, venue, venueRef, student, instructor]. */
    private Object[] setup(int cap) {
        Account ins = account("pay-ins@pd.com", "결제강사");
        ins.setDefaultCapacity(cap);
        accountRepo.save(ins);
        enterInstructorTrack(ins);
        Venue venue = saveVenue(ins);
        String venueRef = VenueScope.token(VenueScope.CUSTOM, String.valueOf(venue.getId()));
        Course course = saveCourse(ins, venueRef, ticketRefOf(venue));
        coverageRepo.save(AvailabilityCoverage.builder()
                .instructor(ins).date(D1).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
        Account student = account("pay-stu@pd.com", "결제학생");
        return new Object[]{course, venue, venueRef, student, ins};
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private void enterInstructorTrack(Account a) {
        applicationRepo.save(InstructorApplication.builder()
                .account(a).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
    }

    private Venue saveVenue(Account owner) {
        VenueTimeBlock b1 = VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build();
        VenueTimeBlock b2 = VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build();
        VenueTimeBlock b1w = VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build();
        VenueTimeBlock b2w = VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build();
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(b1); weekday.addTimeBlock(b2);
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(b1w); weekend.addTimeBlock(b2w);
        VenueTicket ticket = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday); ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(LocalDateTime.now()).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    private String ticketRefOf(Venue v) {
        return v.getTickets().get(0).getRef();
    }

    private Course saveCourse(Account instructor, String venueRefId, String ticketRef) {
        Course course = Course.builder().instructor(instructor).title("AIDA2 프리다이빙 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(350000).status(CourseStatus.OPEN).createdAt(LocalDateTime.now()).build();
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build();
        RoundVenue rv = RoundVenue.builder().venueRefId(venueRefId).sortOrder(0).build();
        rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(rv);
        course.addRound(round);
        return courseRepo.save(course);
    }

    private EnrollmentCreateRequest req(Long courseId, String venueRef, String ticketRef) {
        return EnrollmentCreateRequest.builder()
                .courseId(courseId).date(D1)
                .venueRefId(venueRef).ticketRef(ticketRef)
                .blockStart(B_START).blockEnd(B_END).equipmentRefs(List.of()).build();
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
