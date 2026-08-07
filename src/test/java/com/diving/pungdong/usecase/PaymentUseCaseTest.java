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
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentOrderJpaRepo;
import com.diving.pungdong.payment.PaymentStatus;
import com.diving.pungdong.payment.PaymentGateway;
import com.diving.pungdong.payment.PaymentGatewayRegistry;
import com.diving.pungdong.payment.PaymentProvider;
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
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제(payment) use-case — 실 H2 + Spring Security 필터 + 실 서비스/JPA. 외부 PG 경계인 {@link PaymentGateway} 만
 * {@code @MockBean} 으로 격리(결정적). <b>PG 중립</b> — 토스든 이니시스든 이 사양은 같아야 한다.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. P* 결제 준비·승인 / 보안·멱등.
 *
 * <p>흐름: 학생 신청(PENDING) → 강사 수락(PAYMENT_PENDING) → {@code /payments/prepare}(서버 권위 금액·주문 생성
 * + provider/params 반환) → 결제창 → {@code /payments/confirm}(금액 대조 후 PG 승인 → CONFIRMED). 권위 금액 = 코스 라이브 수강료
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
    @Autowired com.diving.pungdong.identityverification.IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired PaymentOrderJpaRepo orderRepo;

    // 레지스트리를 mock — 어댑터 3개가 모두 빈이라 PaymentGateway 타입으로 mock 하면 주입이 모호해진다.
    @MockBean PaymentGatewayRegistry gateways;
    final PaymentGateway gateway = org.mockito.Mockito.mock(PaymentGateway.class);

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalTime B_START = LocalTime.of(14, 0);
    private static final LocalTime B_END = LocalTime.of(17, 0);

    @BeforeEach
    void stubGatewayApproved() {
        // 기본 — PG 승인은 성공(금액 무관). 승인 자체가 일어나면 안 되는 시나리오는 verify(never()) 로 확인.
        given(gateways.active()).willReturn(gateway);
        given(gateways.forOrder(any())).willReturn(gateway);
        given(gateway.provider()).willReturn(PaymentProvider.STUB);
        // initParams 는 서비스가 넘긴 InitCommand 를 되비춘다 — customerKey 가 제대로 실렸는지 P1 이 검증할 수 있게.
        given(gateway.initParams(any())).willAnswer(inv -> {
            PaymentGateway.InitCommand cmd = inv.getArgument(0);
            return Map.of("customerKey", cmd.customerKey());
        });
        given(gateway.confirm(any()))
                .willAnswer(inv -> new PaymentGateway.ConfirmResult(
                        true, "DONE", "간편결제", OffsetDateTime.now(), null,
                        "pk_test_1")); // 취소 식별자 — 주문에 저장된다
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
        identityVerificationRepo.deleteAll(); // account FK — 계정 삭제 전
        accountRepo.deleteAll();
    }

    /* ─── P* 결제 ─── */

    @Test
    @DisplayName("P1 신청(PENDING) 직후 prepare 하면 서버 권위 금액으로 READY 주문이 생기고 위젯 구동값을 돌려준다")
    void prepareCreatesReadyOrder() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);

        mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(EXPECTED_AMOUNT))
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.provider").value("STUB"))
                .andExpect(jsonPath("$.params.customerKey").value("cust-" + stu.getId()));

        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatus(e.getId(), PaymentStatus.READY).orElseThrow();
        assertThat(order.getAmount()).isEqualTo(EXPECTED_AMOUNT);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("P2 confirm 성공 → PG 승인 후 주문 DONE, 신청 ACCEPT_PENDING(결제완료·강사확인 대기)로 전이된다")
    void confirmConfirmsEnrollment() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);

        mockMvc.perform(post("/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"), "orderId", orderId, "amount", EXPECTED_AMOUNT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.enrollmentStatus").value("ACCEPT_PENDING"));

        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(roundRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
    }

    @Test
    @DisplayName("P3 confirm 의 금액이 서버 권위 금액과 다르면 400 — 토스 호출 안 하고 신청은 그대로")
    void confirmRejectsAmountMismatch() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);

        mockMvc.perform(post("/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"), "orderId", orderId, "amount", 1000))))
                .andExpect(status().isBadRequest());

        verify(gateway, never()).confirm(any()); // 금액 대조에서 막혀 PG 승인 미호출
        assertThat(roundRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("P4 같은 주문을 confirm 두 번 해도 멱등 — 둘째도 200 DONE(이중 승인 없음)")
    void confirmIsIdempotent() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);
        String body = json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"), "orderId", orderId, "amount", EXPECTED_AMOUNT));

        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/payments/confirm").header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.enrollmentStatus").value("ACCEPT_PENDING"));
    }

    @Test
    @DisplayName("P5 이미 결제완료(ACCEPT_PENDING)된 신청에 다시 prepare 하면 400(결제 대기 상태가 아님)")
    void prepareRejectsAlreadyPaid() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);
        // 결제 완료 → ACCEPT_PENDING(강사 확인 대기)
        mockMvc.perform(post("/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("pgPayload", Map.of("paymentKey", "pk_test_1"), "orderId", orderId, "amount", EXPECTED_AMOUNT))))
                .andExpect(status().isOk());

        // 이미 결제완료라 결제 대기 상태가 아님 → 재-prepare 거부
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
        EnrollmentRound e = submitOk(stu, s);
        Account other = account("other@pd.com", "남");

        mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enrollmentId", e.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P7 정원 1 — 첫 신청이 좌석을 lock(선착순), 둘째 신청은 만석 400(수락 전에 신청 단계에서 막힘)")
    void firstComeLocksSeatOnApply() throws Exception {
        Object[] s = setup(1); // 정원 1
        submitOk(account("p7a@pd.com", "학생7A"), s); // 첫 신청 — PENDING 이 1석을 잠금(아직 수락/결제 전)

        Account second = account("p7b@pd.com", "학생7B");
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(second))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(((Course) s[0]).getId(), (String) s[2], ticketRefOf((Venue) s[1])))))
                .andExpect(status().isBadRequest()); // 만석 — 첫 신청이 좌석을 선점
    }

    /* ─── I* 이니시스 콜백 (P_NEXT_URL=BE) ─── */

    @Test
    @DisplayName("I1 이니시스 콜백 — 결제창이 P_NEXT_URL 로 POST 하면 서버가 승인하고(신청 ACCEPT_PENDING) app 성공 스킴으로 302 리다이렉트")
    void inicisCallbackApprovesAndRedirectsApp() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e, "app"); // client=app 박제

        mockMvc.perform(post("/payments/inicis/return") // permitAll — 이니시스 콜백엔 JWT 없음
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("P_OID", orderId)
                .param("P_STATUS", "00").param("P_AUTH_TID", "auth-x").param("P_IDCNAME", "fc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("plop://payment/success")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("orderId=" + orderId)));

        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(roundRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
    }

    @Test
    @DisplayName("I2 이니시스 콜백 — PG 승인 거절이면 주문은 READY 유지, web 실패 URL 로 302(에러를 PG 에 안 던진다)")
    void inicisCallbackRedirectsFailOnDecline() throws Exception {
        given(gateway.confirm(any())) // 이 시나리오만 거절로 덮음
                .willReturn(new PaymentGateway.ConfirmResult(false, "01", null, null, null, null));
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e, "web"); // client=web

        mockMvc.perform(post("/payments/inicis/return")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("P_OID", orderId)
                .param("P_STATUS", "00").param("P_AUTH_TID", "auth-x").param("P_IDCNAME", "fc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://web.test/payment/fail")));

        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(roundRepo.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.PENDING);
    }

    @Test
    @DisplayName("I3 이니시스 콜백 — 인증실패(P_STATUS≠00)면 승인 호출 없이 실패로 302")
    void inicisCallbackAuthFailNoApproval() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e, "web");

        mockMvc.perform(post("/payments/inicis/return")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("P_OID", orderId)
                .param("P_STATUS", "01").param("P_RMESG", "사용자취소"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://web.test/payment/fail")));

        verify(gateway, never()).confirm(any()); // 인증실패면 승인 자체를 안 한다
        assertThat(orderRepo.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("I4 이니시스 콜백 — 알 수 없는 P_OID(위조)면 승인 안 하고 web 실패로 302")
    void inicisCallbackUnknownOrder() throws Exception {
        mockMvc.perform(post("/payments/inicis/return")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("P_OID", "rnd-999-deadbeef").param("P_STATUS", "00"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://web.test/payment/fail")));
    }

    /* ─── O* 주문 상세 조회 ─── */

    @Test
    @DisplayName("O1 결제 완료 후 GET /payments/orders/{orderId} 로 주문 상세(DONE·강사확인 대기)를 조회한다")
    void getOrderReturnsDetail() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);
        mockMvc.perform(post("/payments/inicis/return").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("P_OID", orderId).param("P_STATUS", "00").param("P_AUTH_TID", "auth-x").param("P_IDCNAME", "fc"))
                .andExpect(status().isFound());

        mockMvc.perform(get("/payments/orders/{orderId}", orderId).header(HttpHeaders.AUTHORIZATION, tokenFor(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.amount").value(EXPECTED_AMOUNT))
                .andExpect(jsonPath("$.enrollmentStatus").value("ACCEPT_PENDING"));
    }

    @Test
    @DisplayName("O2 남의 주문을 GET 하면 400(존재 숨김) — 소유권 검증")
    void getOrderHidesOthers() throws Exception {
        Object[] s = setup(4);
        Account stu = (Account) s[3];
        EnrollmentRound e = submitOk(stu, s);
        String orderId = prepareOrderId(stu, e);
        Account other = account("o2other@pd.com", "남");

        mockMvc.perform(get("/payments/orders/{orderId}", orderId).header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isBadRequest());
    }

    /* ─── helpers ─── */

    private String prepareOrderId(Account student, EnrollmentRound e) throws Exception {
        return prepareOrderId(student, e, null);
    }

    private String prepareOrderId(Account student, EnrollmentRound e, String client) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("enrollmentId", e.getId());
        if (client != null) {
            body.put("client", client);
        }
        String resp = mockMvc.perform(post("/payments/prepare")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("orderId").asText();
    }

    private EnrollmentRound submitOk(Account student, Object[] s) throws Exception {
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        String ticketRef = ticketRefOf((Venue) s[1]);
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRef))))
                .andExpect(status().isCreated());
        return roundRepo.findByEnrollment_Student_IdOrderByIdDesc(student.getId()).get(0);
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
        Account a = accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        identityVerificationRepo.save(com.diving.pungdong.identityverification.IdentityVerification.builder()
                .account(a).status(com.diving.pungdong.identityverification.IdentityVerificationStatus.VERIFIED)
                .verifiedAt(OffsetDateTime.now(ZoneOffset.UTC)).build()); // 수강신청 게이트 통과용 본인인증
        return a;
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private void enterInstructorTrack(Account a) {
        applicationRepo.save(InstructorApplication.builder()
                .account(a).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
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
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    private String ticketRefOf(Venue v) {
        return v.getTickets().get(0).getRef();
    }

    private Course saveCourse(Account instructor, String venueRefId, String ticketRef) {
        Course course = Course.builder().instructor(instructor).title("AIDA2 프리다이빙 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(350000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
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
