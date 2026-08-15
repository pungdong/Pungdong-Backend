package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.PaymentGatewayException;
import com.diving.pungdong.global.advice.exception.RefundBlockedException;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentOrderJpaRepo;
import com.diving.pungdong.payment.PaymentStatus;
import com.diving.pungdong.payment.RefundOrderJpaRepo;
import com.diving.pungdong.payment.RefundStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 환불 use-case — 수강 종료(남은 회차 환불). 실 H2 + 시큐리티 + 실 서비스, 외부 PG 경계만 mock. 수강료가
 * 1회차 주문에 전액 있으므로 <b>2회차 수강료 몫도 1회차 주문 부분취소</b>로 빠지는 게 핵심.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. RF1 산정·기록 / RF2 PG 전달값 / RF3 재환불 차단.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired PaymentOrderJpaRepo orderRepo;
    @Autowired RefundOrderJpaRepo refundRepo;
    @Autowired com.diving.pungdong.payment.PaymentApprovalJpaRepo approvalRepo;
    @Autowired com.diving.pungdong.notification.UserNotificationJpaRepo userNotificationRepo;
    @Autowired com.diving.pungdong.enrollment.InstructorEnrollmentService instructorEnrollmentService;
    @Autowired com.diving.pungdong.enrollment.EnrollmentExpiryService expiryService;
    @Autowired com.diving.pungdong.payment.RefundService refundService;

    // 레지스트리를 mock — 어댑터 3개가 모두 빈이라 PaymentGateway 타입 mock 은 주입이 모호해진다.
    // cancel 인자·"어느 PG 로 갔는지" 검증 + 이제 반환값(canceled)도 서비스가 본다(H-2) → 기본 stub 로 확정 취소.
    @MockBean com.diving.pungdong.payment.PaymentGatewayRegistry gateways;
    final com.diving.pungdong.payment.PaymentGateway gateway =
            org.mockito.Mockito.mock(com.diving.pungdong.payment.PaymentGateway.class);

    @org.junit.jupiter.api.BeforeEach
    void routeToMock() {
        org.mockito.BDDMockito.given(gateways.active()).willReturn(gateway);
        org.mockito.BDDMockito.given(gateways.forOrder(org.mockito.ArgumentMatchers.any())).willReturn(gateway);
        // 기본 — PG 취소는 확정 성공. 미확정/거절 시나리오는 각 테스트가 override.
        org.mockito.BDDMockito.given(gateway.cancel(anyString(), anyInt(), anyInt(), anyString()))
                .willReturn(new com.diving.pungdong.payment.PaymentGateway.CancelResult(true, "CANCELED", OffsetDateTime.now()));
    }

    @AfterEach
    void clean() {
        userNotificationRepo.deleteAll(); // enqueue 가 남긴 알림함 행
        refundRepo.deleteAll();
        approvalRepo.deleteAll(); // payment_order FK — 주문 삭제 전
        orderRepo.deleteAll();
        enrollmentRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private EnrollmentRound round(int idx, EnrollmentStatus status, LocalDate date, boolean done, int extras) {
        return EnrollmentRound.builder()
                .roundIndex(idx).roundKind(RoundKind.REGULAR).status(status).date(date)
                .blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0)).venueRefId("CUSTOM:1")
                .respondedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2)).createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .doneAt(done ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(1) : null)
                .entrySnapshot(extras).equipmentSnapshot(0).extraSnapshot(0).build();
    }

    private void order(EnrollmentRound r, int amount, String key) {
        orderRepo.save(PaymentOrder.builder()
                .orderId("ord-" + key).enrollmentRound(r).amount(amount).orderName("결제")
                .status(PaymentStatus.DONE).paymentKey(key).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    @Test
    @DisplayName("RF1 수강 환불 — done=0, 2회차 배정취소(수강료/N+부대)×100%, 수강료 몫은 1회차 주문 부분취소")
    void refundEnrollment() throws Exception {
        Account stu = accountRepo.save(Account.builder().email("rf@pd.com").password("x").nickName("학생")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi@pd.com").password("x").nickName("강사")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("2회차 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(200000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(2).build());
        courseRepo.save(course);

        // 수강료 200,000 / 2회차 → 회차당 100,000.
        EnrollmentRound r1 = round(1, EnrollmentStatus.CONFIRMED, LocalDate.now().minusDays(3), true, 20000);   // done
        EnrollmentRound r2 = round(2, EnrollmentStatus.CONFIRMED, LocalDate.now().plusDays(5), false, 20000);   // 3일전+
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(200000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r1);
        e.addRound(r2);
        enrollmentRepo.save(e);
        order(r1, 220000, "pk1"); // 수강료 200,000 + 1회차 부대 20,000
        order(r2, 20000, "pk2");  // 2회차 부대 20,000

        mockMvc.perform(post("/enrollments/{id}/refund", e.getId()).header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                // 2회차 = (100,000 + 20,000)×100% = 120,000 (1회차 done = 0)
                .andExpect(jsonPath("$.total").value(120000))
                .andExpect(jsonPath("$.lines.length()").value(2));

        // 2회차 CANCELLED, 1회차(done) 유지
        assertThat(roundRepo.findById(r2.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(roundRepo.findById(r1.getId()).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        // 환불 주문 2건: 1회차 주문 100,000(2회차 수강료 몫) + 2회차 주문 20,000(부대)
        assertThat(refundRepo.findAll()).hasSize(2);
        assertThat(refundRepo.findAll().stream().mapToInt(com.diving.pungdong.payment.RefundOrder::getAmount).sum())
                .isEqualTo(120000);
    }

    @Test
    @DisplayName("RF2 주문별 취소액과 취소가능잔액이 정확히 전달된다 — 잔액이 틀리면 부분취소가 전액취소로 나간다")
    void cancelArgumentsAreExact() throws Exception {
        Fixture f = fixture();

        mockMvc.perform(post("/enrollments/{id}/refund", f.enrollmentId).header(HttpHeaders.AUTHORIZATION, token(f.student)))
                .andExpect(status().isOk());

        // 1회차 주문(220,000) 중 100,000 만 취소 → 취소액 < 잔액 = 부분취소 경로.
        verify(gateway).cancel(eq("pk1"), eq(100_000), eq(220_000), anyString());
        // 2회차 주문(20,000) 전액 취소 → 취소액 == 잔액 = 전체취소 경로.
        verify(gateway).cancel(eq("pk2"), eq(20_000), eq(20_000), anyString());
    }

    @Test
    @DisplayName("RF3 이미 환불한 수강을 다시 환불하면 400 — 활성 회차가 없어 중복 취소가 원천 차단된다")
    void secondRefundRejected() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(post("/enrollments/{id}/refund", f.enrollmentId).header(HttpHeaders.AUTHORIZATION, token(f.student)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/enrollments/{id}/refund", f.enrollmentId).header(HttpHeaders.AUTHORIZATION, token(f.student)))
                .andExpect(status().isBadRequest());

        assertThat(refundRepo.findAll()).hasSize(2); // 첫 환불분만 — 두 번째로 늘지 않는다
    }

    @Test
    @DisplayName("RF4 강사 미수락(ACCEPT_PENDING) 회차는 수강종료 시 당일 세션이어도 100% — 날짜 페널티는 CONFIRMED만")
    void acceptPendingFullRefundEvenSameDay() throws Exception {
        Account stu = accountRepo.save(Account.builder().email("rf4@pd.com").password("x").nickName("학생4")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi4@pd.com").password("x").nickName("강사4")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("1회차 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(100000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);

        // 강사 미수락(ACCEPT_PENDING) + 세션이 바로 오늘(당일) — CONFIRMED 였으면 0% 였을 조건.
        EnrollmentRound r1 = round(1, EnrollmentStatus.ACCEPT_PENDING, LocalDate.now(), false, 20000);
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(100000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r1);
        enrollmentRepo.save(e);
        order(r1, 120000, "pk-ap"); // 수강료 100,000 + 부대 20,000

        mockMvc.perform(post("/enrollments/{id}/refund", e.getId()).header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(120000)); // 당일이지만 미수락 → 전액. cancel(roundId) 경로와 일치.
    }

    @Test
    @DisplayName("RF16 강사 수락 직후(1h 내) 당일 세션 수강종료는 100% — 그레이스는 수락 시각에서 센다(결제가 몇 시간 전이어도)")
    void graceRunsFromAcceptanceNotPayment() throws Exception {
        // staging 2026-08-15 재현: 신청·결제 5h 전 → 강사 수락 2분 전 → 곧장 환불. 세션은 오늘(당일=0% 조건).
        Account stu = accountRepo.save(Account.builder().email("rf16@pd.com").password("x").nickName("학생16")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi16@pd.com").password("x").nickName("강사16")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("1회차 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(100000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EnrollmentRound r1 = round(1, EnrollmentStatus.CONFIRMED, LocalDate.now(), false, 20000);
        r1.setCreatedAt(now.minusHours(5));    // 신청·결제 5시간 전
        r1.setRespondedAt(now.minusMinutes(2)); // 강사 수락 2분 전 = 확정 시각
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(100000)
                .createdAt(now.minusHours(5)).build();
        e.addRound(r1);
        enrollmentRepo.save(e);
        order(r1, 120000, "pk-grace"); // 수강료 100,000 + 부대 20,000
        orderRepo.findAll().stream().filter(o -> "pk-grace".equals(o.getPaymentKey())).forEach(o -> {
            o.setApprovedAt(now.minusHours(5)); // 결제완료 5시간 전 — 이걸 앵커로 쓰면(#258) 그레이스 만료였다
            orderRepo.save(o);
        });

        mockMvc.perform(post("/enrollments/{id}/refund", e.getId()).header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(120000)); // 결제 시각 기준이었으면 당일 0% 로 0 이 나왔을 것
    }

    /* ─── 어드민 수동 환불 (운영 보정 — PG 콘솔 대신 코드로, 원장에 남게) ─── */

    private Account admin(String mail) {
        return accountRepo.save(Account.builder().email(mail).password("x").nickName(mail.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.ADMIN))).build());
    }

    private Fixture cancelledWithLeftover() {
        // staging 2026-08-15 재현: 정책 오산정으로 부분환불된 뒤 회차는 CANCELLED, 주문엔 잔액이 남아 있다.
        Fixture f = fixture();
        var order = orderRepo.findByOrderId("ord-pk1").orElseThrow();
        order.setRefundedAmount(233334); // 427,000 결제 · 233,334 환불 → 잔액 193,666
        order.setAmount(427000);
        orderRepo.save(order);
        return f;
    }

    @Test
    @DisplayName("RF17 어드민 수동 환불 — 주문 잔액 전액(amount 생략) 을 PG 취소하고 RefundOrder 원장 + 잔액에 남긴다")
    void adminManualRefundRemaining() throws Exception {
        Fixture f = cancelledWithLeftover();
        var order = orderRepo.findByOrderId("ord-pk1").orElseThrow();
        Account admin = admin("adm17@pd.com");

        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"그레이스 정책 오산정 보정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(193666))
                .andExpect(jsonPath("$.refundedTotal").value(427000))
                .andExpect(jsonPath("$.refundable").value(0));

        // PG 엔 잔액 전액 취소 + 취소가능잔액이 그대로 전달됐다.
        org.mockito.Mockito.verify(gateway).cancel(org.mockito.ArgumentMatchers.eq(order.getPaymentKey()),
                org.mockito.ArgumentMatchers.eq(193666), org.mockito.ArgumentMatchers.eq(193666), anyString());
        // 원장: DONE 한 줄, 사유 접두 "운영자 수동 환불: ", 주문은 전액환불이라 CANCELED.
        var rows = refundRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAmount()).isEqualTo(193666);
        assertThat(rows.get(0).getReason()).startsWith("운영자 수동 환불: ");
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getRefundedAmount()).isEqualTo(427000);
        assertThat(orderRepo.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("RF18 어드민 수동 환불 — 잔액을 넘는 금액은 400(clamp 하지 않음), 사유 없으면 400, 이미 전액 환불이면 400")
    void adminManualRefundRejectsBadAmounts() throws Exception {
        Fixture f = cancelledWithLeftover();
        var order = orderRepo.findByOrderId("ord-pk1").orElseThrow();
        Account admin = admin("adm18@pd.com");

        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"amount\":193667,\"reason\":\"초과\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isBadRequest());
        assertThat(refundRepo.findAll()).isEmpty(); // 아무것도 안 나감

        // 일부(1,000) → 성공, 그 뒤 잔액 0 이 되면 400
        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"amount\":193666,\"reason\":\"전액\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"또\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("RF19 어드민 수동 환불은 ADMIN 만 — 학생·강사 토큰은 403, 원장에 아무것도 안 남는다")
    void adminManualRefundForbiddenForNonAdmin() throws Exception {
        Fixture f = cancelledWithLeftover();
        var order = orderRepo.findByOrderId("ord-pk1").orElseThrow();
        mockMvc.perform(post("/admin/payments/orders/{orderId}/refund", order.getOrderId())
                        .header(HttpHeaders.AUTHORIZATION, token(f.student))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"내 돈\"}"))
                .andExpect(status().isForbidden());
        assertThat(refundRepo.findAll()).isEmpty();
    }

    /* ─── fixture ─── */

    private static class Fixture {
        Account student;
        Long enrollmentId;
    }

    /** RF1 과 동일 구성: 2회차 과정(수강료 200,000), 1회차 done, 2회차 3일전+. 주문 pk1=220,000 / pk2=20,000. */
    private Fixture fixture() {
        Account stu = accountRepo.save(Account.builder().email("rf2@pd.com").password("x").nickName("학생2")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi2@pd.com").password("x").nickName("강사2")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("2회차 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(200000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(2).build());
        courseRepo.save(course);

        EnrollmentRound r1 = round(1, EnrollmentStatus.CONFIRMED, LocalDate.now().minusDays(3), true, 20000);
        EnrollmentRound r2 = round(2, EnrollmentStatus.CONFIRMED, LocalDate.now().plusDays(5), false, 20000);
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(200000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r1);
        e.addRound(r2);
        enrollmentRepo.save(e);
        order(r1, 220000, "pk1");
        order(r2, 20000, "pk2");

        Fixture f = new Fixture();
        f.student = stu;
        f.enrollmentId = e.getId();
        return f;
    }

    @Test
    @DisplayName("RF4 PG 를 갈아탄 뒤에도 과거 주문의 환불은 결제 당시 PG 로 나간다 — 전역 설정을 보지 않는다")
    void refundRoutesToOrderProviderAfterSwitch() throws Exception {
        Fixture f = fixture();
        // 이 주문들은 이니시스로 결제된 것으로 박제한다(결제 당시 PG).
        orderRepo.findAll().forEach(o -> {
            o.setProvider(com.diving.pungdong.payment.PaymentProvider.INICIS);
            orderRepo.save(o);
        });

        // 그 뒤 전역 설정이 토스로 바뀐 상황 — active() 는 토스, forOrder(INICIS) 는 이니시스를 준다.
        var toss = org.mockito.Mockito.mock(com.diving.pungdong.payment.PaymentGateway.class);
        var inicis = org.mockito.Mockito.mock(com.diving.pungdong.payment.PaymentGateway.class);
        org.mockito.BDDMockito.given(gateways.active()).willReturn(toss);
        org.mockito.BDDMockito.given(gateways.forOrder(com.diving.pungdong.payment.PaymentProvider.INICIS)).willReturn(inicis);
        org.mockito.BDDMockito.given(inicis.cancel(anyString(), anyInt(), anyInt(), anyString()))
                .willReturn(new com.diving.pungdong.payment.PaymentGateway.CancelResult(true, "CANCELED", OffsetDateTime.now()));

        mockMvc.perform(post("/enrollments/{id}/refund", f.enrollmentId).header(HttpHeaders.AUTHORIZATION, token(f.student)))
                .andExpect(status().isOk());

        // 취소는 전부 이니시스로. 토스로 한 건이라도 나가면 "존재하지 않는 거래" 취소라 돈은 받고 환불은 실패한다.
        verify(inicis).cancel(eq("pk1"), eq(100_000), eq(220_000), anyString());
        verify(inicis).cancel(eq("pk2"), eq(20_000), eq(20_000), anyString());
        org.mockito.Mockito.verifyNoInteractions(toss);
    }

    /* ─── RF5·RF6 선결제 자동환불 (강사 거절 / 무응답 만료) ─── */

    @Test
    @DisplayName("RF5 강사가 결제완료(ACCEPT_PENDING) 신청을 거절 → REJECTED + 결제된 주문 전액 자동환불(cancel 호출·환불기록)")
    void rejectRefundsPaidOrder() {
        Account stu = accountRepo.save(Account.builder().email("rf5@pd.com").password("x").nickName("학생5")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi5@pd.com").password("x").nickName("강사5")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 350000, "pkR");

        instructorEnrollmentService.reject(ins, roundId, "일정이 안 맞아요");

        assertThat(roundRepo.findById(roundId).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.REJECTED);
        // 결제 당시 PG 로 전액 취소(cancelAmount == 잔액 == 결제액).
        verify(gateway).cancel(eq("pkR"), eq(350000), eq(350000), anyString());
        assertThat(refundRepo.findAll()).hasSize(1);
        assertThat(refundRepo.findAll().get(0).getAmount()).isEqualTo(350000);
    }

    @Test
    @DisplayName("RF6 결제완료(ACCEPT_PENDING) 신청이 강사 무응답으로 만료 → CANCELLED + 전액 자동환불")
    void expiryRefundsPaidOrder() {
        Account stu = accountRepo.save(Account.builder().email("rf6@pd.com").password("x").nickName("학생6")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi6@pd.com").password("x").nickName("강사6")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        // round() 헬퍼가 respondedAt = 2일 전으로 세팅 → pendingTtlHours(24h) 초과 = 만료 대상.
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 350000, "pkE");

        int expired = expiryService.sweepExpired(OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(expired).isEqualTo(1);
        assertThat(roundRepo.findById(roundId).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        verify(gateway).cancel(eq("pkE"), eq(350000), eq(350000), anyString());
        assertThat(refundRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("RF7 부분환불이 누적되면 주문 잔액이 줄고(refundedAmount), 잔액을 넘는 환불은 clamp 된다")
    void partialRefundsAccumulateAndClamp() {
        Account stu = accountRepo.save(Account.builder().email("rf7@pd.com").password("x").nickName("학생7")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi7@pd.com").password("x").nickName("강사7")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 20000, "pkP");

        refundService.refundRoundPartially(roundId, 2000, "일정 변경 차액");
        PaymentOrder after1 = orderRepo.findByOrderId("ord-pkP").orElseThrow();
        assertThat(after1.getRefundedAmount()).isEqualTo(2000);
        assertThat(after1.refundableAmount()).isEqualTo(18000);
        assertThat(after1.getStatus()).isEqualTo(PaymentStatus.DONE); // 부분환불은 DONE 유지

        refundService.refundRoundPartially(roundId, 3000, "일정 변경 차액");
        PaymentOrder after2 = orderRepo.findByOrderId("ord-pkP").orElseThrow();
        assertThat(after2.getRefundedAmount()).isEqualTo(5000); // 누적
        assertThat(after2.refundableAmount()).isEqualTo(15000);

        // 잔액(15,000)을 넘겨 요청해도 잔액까지만 취소된다
        refundService.refundRoundPartially(roundId, 999999, "과다 요청");
        PaymentOrder after3 = orderRepo.findByOrderId("ord-pkP").orElseThrow();
        assertThat(after3.getRefundedAmount()).isEqualTo(20000);
        assertThat(after3.refundableAmount()).isZero();
        verify(gateway).cancel(eq("pkP"), eq(15000), eq(15000), anyString()); // 초과분이 아니라 잔액만
        assertThat(refundRepo.findAll()).hasSize(3); // 이력은 3행 그대로 남는다(원장)
    }

    @Test
    @DisplayName("RF8 전액 환불되면 주문 상태가 CANCELED 로 바뀌고, 이후 환불 호출은 no-op(멱등)")
    void fullRefundMarksOrderCanceled() {
        Account stu = accountRepo.save(Account.builder().email("rf8@pd.com").password("x").nickName("학생8")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi8@pd.com").password("x").nickName("강사8")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 50000, "pkF");

        refundService.refundRoundFully(roundId, "강사 거절");
        PaymentOrder after = orderRepo.findByOrderId("ord-pkF").orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualTo(50000);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELED); // 테이블만 봐도 전액환불이 보인다

        // 다시 호출해도 PG 를 두 번 부르지 않는다(잔액 0 → no-op)
        refundService.refundRoundFully(roundId, "중복 호출");
        refundService.refundRoundPartially(roundId, 1000, "중복 호출");
        verify(gateway, org.mockito.Mockito.times(1)).cancel(eq("pkF"), anyInt(), anyInt(), anyString());
        assertThat(refundRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("RF9 PG 가 환불을 거절하면 상태전이는 롤백되지만 실패 이력(FAILED + PG 코드/사유)은 남는다")
    void failedRefundLeavesLedgerRow() {
        Account stu = accountRepo.save(Account.builder().email("rf9@pd.com").password("x").nickName("학생9")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi9@pd.com").password("x").nickName("강사9")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 30000, "pkX");
        // PG 가 거절 — 어댑터는 진단정보를 실은 PaymentGatewayException 을 던진다.
        org.mockito.BDDMockito.willThrow(new PaymentGatewayException("9001", "잔액 부족"))
                .given(gateway).cancel(eq("pkX"), anyInt(), anyInt(), anyString());

        assertThatThrownBy(() -> instructorEnrollmentService.reject(ins, roundId, "일정 안 맞음"))
                .isInstanceOf(PaymentGatewayException.class);

        // 상태전이는 롤백 — 환불이 안 됐으니 거절도 확정되면 안 된다(돈-상태 원자성)
        assertThat(roundRepo.findById(roundId).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
        // 그러나 시도 이력은 별도 트랜잭션이라 남는다 — 재시도·대사의 근거
        var ledgerRows = refundRepo.findAll();
        assertThat(ledgerRows).hasSize(1);
        assertThat(ledgerRows.get(0).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(ledgerRows.get(0).getFailureCode()).isEqualTo("9001");
        assertThat(ledgerRows.get(0).getFailureMessage()).isEqualTo("잔액 부족");
        assertThat(ledgerRows.get(0).getCompletedAt()).isNotNull();
        // 실패했으므로 잔액은 그대로(환불 안 됨)
        assertThat(orderRepo.findByOrderId("ord-pkX").orElseThrow().getRefundedAmount()).isZero();
    }

    @Test
    @DisplayName("RF10 결과 미확인 시도(REQUESTED 잔존)면 자동 환불을 막고 RefundBlockedException — PG 재호출·새 시도 없음")
    void unresolvedAttemptBlocksAutoRefund() {
        Account stu = accountRepo.save(Account.builder().email("rf10@pd.com").password("x").nickName("학생10")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi10@pd.com").password("x").nickName("강사10")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 30000, "pkU");
        PaymentOrder order = orderRepo.findByOrderId("ord-pkU").orElseThrow();
        // 전송 실패로 결과를 못 받은 시도가 남아 있는 상황(프로세스 급사 등)
        refundRepo.save(com.diving.pungdong.payment.RefundOrder.builder()
                .paymentOrder(order).amount(30000).reason("이전 시도")
                .status(RefundStatus.REQUESTED).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        // 옛 동작(조용히 return 0)은 발행자를 커밋시켜 돈만 남겼다 → 이제 던져서 발행자까지 롤백시킨다(C2).
        assertThatThrownBy(() -> refundService.refundRoundFully(roundId, "강사 거절"))
                .isInstanceOf(RefundBlockedException.class);

        // PG 를 다시 부르지 않는다 — 이미 취소됐을 수 있으므로 사람이 대사해야 한다
        verify(gateway, org.mockito.Mockito.never()).cancel(eq("pkU"), anyInt(), anyInt(), anyString());
        assertThat(orderRepo.findByOrderId("ord-pkU").orElseThrow().getRefundedAmount()).isZero();
        assertThat(refundRepo.findAll()).hasSize(1); // 새 시도 행도 안 생김
    }

    @Test
    @DisplayName("RF14 PG 가 취소를 확정하지 않으면(canceled=false) DONE 으로 기록하지 않고 FAILED + 롤백 (H-2)")
    void unconfirmedCancelNotRecordedDone() {
        Account stu = accountRepo.save(Account.builder().email("rf14@pd.com").password("x").nickName("학생14")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi14@pd.com").password("x").nickName("강사14")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 30000, "pkNC");
        // PG 가 2xx 를 주지만 취소를 확정하지 않은 상태(canceled=false) — 예: 비동기·미지원 상태
        org.mockito.BDDMockito.given(gateway.cancel(anyString(), anyInt(), anyInt(), anyString()))
                .willReturn(new com.diving.pungdong.payment.PaymentGateway.CancelResult(false, "IN_PROGRESS", OffsetDateTime.now()));

        assertThatThrownBy(() -> refundService.refundRoundFully(roundId, "강사 거절"))
                .isInstanceOf(PaymentGatewayException.class);

        // DONE 으로 기록되지 않는다 — 잔액 그대로, 이력은 FAILED(대사 대상)
        assertThat(orderRepo.findByOrderId("ord-pkNC").orElseThrow().getRefundedAmount()).isZero();
        assertThat(refundRepo.findAll()).allMatch(r -> r.getStatus() == RefundStatus.FAILED);
    }

    @Test
    @DisplayName("RF15 결과 미확인 시도가 있으면 강사 거절이 롤백된다 — 회차가 REJECTED 로 확정되지 않는다 (C2)")
    void rejectRollsBackWhenRefundBlocked() {
        Account stu = accountRepo.save(Account.builder().email("rf15@pd.com").password("x").nickName("학생15")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi15@pd.com").password("x").nickName("강사15")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 30000, "pkRB");
        PaymentOrder order = orderRepo.findByOrderId("ord-pkRB").orElseThrow();
        refundRepo.save(com.diving.pungdong.payment.RefundOrder.builder()
                .paymentOrder(order).amount(30000).reason("이전 시도")
                .status(RefundStatus.REQUESTED).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        // 거절 → 동기 이벤트 → refundRoundFully 가 막힘 → 거절 트랜잭션 전체 롤백
        assertThatThrownBy(() -> instructorEnrollmentService.reject(ins, roundId, "거절 사유"))
                .isInstanceOf(RefundBlockedException.class);

        // 회차는 REJECTED 로 확정되지 않고 ACCEPT_PENDING 유지(돈만 남는 상태를 막음)
        assertThat(roundRepo.findById(roundId).orElseThrow().getStatus()).isEqualTo(EnrollmentStatus.ACCEPT_PENDING);
    }

    @Test
    @DisplayName("RF11 한 회차에 주문이 여러 건이면(원결제+차액) 전액 환불은 각 주문을 모두 취소한다")
    void fullRefundCancelsEveryOrderOfRound() {
        Account stu = accountRepo.save(Account.builder().email("rf11@pd.com").password("x").nickName("학생11")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi11@pd.com").password("x").nickName("강사11")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 20000, "pkA"); // 원결제 20,000
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow();
        order(r, 5000, "pkB"); // 더 비싼 슬롯으로 옮기며 낸 차액 5,000

        refundService.refundRoundFully(roundId, "강사 거절");

        // 주문 단위로 각각 취소된다(PG 취소 전문은 그 주문의 tid 를 실어야 하므로)
        verify(gateway).cancel(eq("pkA"), eq(20000), eq(20000), anyString());
        verify(gateway).cancel(eq("pkB"), eq(5000), eq(5000), anyString());
        assertThat(orderRepo.findByOrderId("ord-pkA").orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(orderRepo.findByOrderId("ord-pkB").orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(refundRepo.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("RF12 차액 환불은 최신 주문부터 뺀다 — 원결제가 부분환불된 것처럼 보이지 않게")
    void partialRefundTakesFromNewestOrderFirst() {
        Account stu = accountRepo.save(Account.builder().email("rf12@pd.com").password("x").nickName("학생12")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi12@pd.com").password("x").nickName("강사12")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 20000, "pkC"); // 원결제
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow();
        order(r, 5000, "pkD"); // 차액 결제

        refundService.refundRoundPartially(roundId, 3000, "일정 변경 차액");

        verify(gateway).cancel(eq("pkD"), eq(3000), eq(5000), anyString());   // 차액 주문에서만
        verify(gateway, org.mockito.Mockito.never()).cancel(eq("pkC"), anyInt(), anyInt(), anyString());
        assertThat(orderRepo.findByOrderId("ord-pkC").orElseThrow().getRefundedAmount()).isZero();
        assertThat(orderRepo.findByOrderId("ord-pkD").orElseThrow().getRefundedAmount()).isEqualTo(3000);
    }

    @Test
    @DisplayName("RF13 차액 환불이 최신 주문 잔액을 넘으면 이전 주문으로 넘어가 채우고, 회차 순액을 넘진 않는다")
    void partialRefundSpillsOverToOlderOrderAndClampsToRoundNet() {
        Account stu = accountRepo.save(Account.builder().email("rf13@pd.com").password("x").nickName("학생13")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rfi13@pd.com").password("x").nickName("강사13")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Long roundId = paidSingleRound(stu, ins, EnrollmentStatus.ACCEPT_PENDING, 20000, "pkE2"); // 원결제 20,000
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow();
        order(r, 5000, "pkF2"); // 차액 5,000 — 회차 순액 25,000

        refundService.refundRoundPartially(roundId, 999999, "과다 요청");

        // 최신(5,000) 먼저 → 남은 20,000 을 원결제에서. 회차 순액 25,000 을 넘지 않는다.
        verify(gateway).cancel(eq("pkF2"), eq(5000), eq(5000), anyString());
        verify(gateway).cancel(eq("pkE2"), eq(20000), eq(20000), anyString());
        assertThat(orderRepo.findByOrderId("ord-pkE2").orElseThrow().getRefundedAmount()).isEqualTo(20000);
        assertThat(orderRepo.findByOrderId("ord-pkF2").orElseThrow().getRefundedAmount()).isEqualTo(5000);
    }

    /** 1회차 선결제 완료 엔롤 + DONE 주문 한 건. roundId 반환(거절/만료 대상). */
    private Long paidSingleRound(Account stu, Account ins, EnrollmentStatus status, int amount, String key) {
        Course course = Course.builder().instructor(ins).title("프리다이빙 1일 레슨")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(amount).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);

        EnrollmentRound r1 = round(1, status, LocalDate.now().plusDays(5), false, 0);
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(amount)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r1);
        enrollmentRepo.save(e);
        order(r1, amount, key);
        return r1.getId();
    }

    /**
     * 학생이 <b>결제완료 회차를 직접 취소</b>하면 자동환불이 돌고 <b>환불 완료 알림</b>이 간다.
     *
     * <p>거절·만료로 인한 자동환불은 그쪽 알림 body 가 환불을 안내하므로 알림을 안 보내지만,
     * 이 경로엔 알려주는 알림이 <b>따로 없다</b> — 그래서 여기서만 보낸다({@code studentInitiated}).
     * 금액은 <b>실제 반환액</b>이어야 한다(계획액을 쓰면 clamp/스킵 시 문구가 거짓이 된다).
     */
    @Test
    @DisplayName("RF4 학생이 결제완료 회차를 직접 취소하면 자동환불 + 실제 환불액으로 REFUND_COMPLETED 알림이 간다")
    void studentCancelNotifiesRefund() throws Exception {
        Account stu = accountRepo.save(Account.builder().email("rf4@pd.com").password("x").nickName("학생4")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("rf4i@pd.com").password("x").nickName("강사4")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("취소될 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(100000).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);

        EnrollmentRound r = round(1, EnrollmentStatus.ACCEPT_PENDING, LocalDate.now().plusDays(10), false, 0);
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(100000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r);
        enrollmentRepo.save(e);
        order(r, 100000, "pk-rf4");

        mockMvc.perform(post("/enrollments/{id}/cancel", r.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk());

        // 환불이 실제로 나갔고
        org.mockito.Mockito.verify(gateway)
                .cancel(org.mockito.ArgumentMatchers.eq("pk-rf4"), org.mockito.ArgumentMatchers.eq(100000),
                        org.mockito.ArgumentMatchers.eq(100000), org.mockito.ArgumentMatchers.anyString());
        // 그 금액 그대로 알림이 갔다
        var inbox = userNotificationRepo.findAll().stream()
                .filter(n -> stu.getId().equals(n.getRecipientAccountId()))
                .collect(java.util.stream.Collectors.toList());
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).getType())
                .isEqualTo(com.diving.pungdong.notification.NotificationType.REFUND_COMPLETED);
        assertThat(inbox.get(0).getBody()).contains("100,000원이 환불되었어요");
    }
}
