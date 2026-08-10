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
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentOrderJpaRepo;
import com.diving.pungdong.payment.PaymentStatus;
import com.diving.pungdong.payment.RefundOrderJpaRepo;
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
    @Autowired com.diving.pungdong.enrollment.InstructorEnrollmentService instructorEnrollmentService;
    @Autowired com.diving.pungdong.enrollment.EnrollmentExpiryService expiryService;
    @Autowired com.diving.pungdong.payment.RefundService refundService;

    // 레지스트리를 mock — 어댑터 3개가 모두 빈이라 PaymentGateway 타입 mock 은 주입이 모호해진다.
    // cancel 에 넘어가는 인자와 "어느 PG 로 갔는지"를 검증하기 위해(반환값은 서비스가 쓰지 않는다).
    @MockBean com.diving.pungdong.payment.PaymentGatewayRegistry gateways;
    final com.diving.pungdong.payment.PaymentGateway gateway =
            org.mockito.Mockito.mock(com.diving.pungdong.payment.PaymentGateway.class);

    @org.junit.jupiter.api.BeforeEach
    void routeToMock() {
        org.mockito.BDDMockito.given(gateways.active()).willReturn(gateway);
        org.mockito.BDDMockito.given(gateways.forOrder(org.mockito.ArgumentMatchers.any())).willReturn(gateway);
    }

    @AfterEach
    void clean() {
        refundRepo.deleteAll();
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
}
