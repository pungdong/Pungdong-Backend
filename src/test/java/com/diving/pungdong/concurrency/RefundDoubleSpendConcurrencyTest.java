package com.diving.pungdong.concurrency;

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
import com.diving.pungdong.payment.PaymentApprovalJpaRepo;
import com.diving.pungdong.payment.PaymentGateway;
import com.diving.pungdong.payment.PaymentGatewayRegistry;
import com.diving.pungdong.payment.PaymentOrder;
import com.diving.pungdong.payment.PaymentOrderJpaRepo;
import com.diving.pungdong.payment.PaymentStatus;
import com.diving.pungdong.payment.RefundOrder;
import com.diving.pungdong.payment.RefundOrderJpaRepo;
import com.diving.pungdong.payment.RefundService;
import com.diving.pungdong.payment.RefundStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * H-1 이중환불 동시성 — 두 환불 발행자(학생 취소·강사 거절·만료 스윕)가 <b>같은 주문을 동시에</b> 환불하려 할 때
 * PG 취소가 <b>정확히 한 번만</b> 나가는지를 <b>실 MySQL</b>에서 검증한다. H2 는 {@code SELECT FOR UPDATE}·조건부
 * 유니크의 동시성 의미를 재현 못 해 이 테스트를 못 돌린다 — 그래서 Testcontainers 로 prod 와 같은 엔진·스키마에서 돈다.
 *
 * <p><b>방어 기제</b>: applyCancel 의 refundable 조회 → hasUnresolvedAttempt 가드 → recordAttempt 는 락 없는
 * check-then-insert 라 near-simultaneous 두 스레드가 둘 다 가드를 통과할 수 있다. 최종 원자 차단은
 * {@code uk_refund_order_inflight}(V25) — 주문당 REQUESTED 1개. 진 스레드의 recordAttempt 는 유니크 위반으로
 * PG 취소 전에 걸러져 그 발행자가 롤백된다. 결과: cancel 1회, refundedAmount == 승인액(2배 아님).
 */
class RefundDoubleSpendConcurrencyTest extends MySqlConcurrencyTestBase {

    @Autowired RefundService refundService;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired PaymentOrderJpaRepo orderRepo;
    @Autowired RefundOrderJpaRepo refundRepo;
    @Autowired PaymentApprovalJpaRepo approvalRepo;

    // 어댑터 3개가 모두 빈이라 PaymentGateway 타입 mock 은 주입이 모호 → 레지스트리를 mock (RefundUseCaseTest 와 동일).
    @MockBean PaymentGatewayRegistry gateways;
    final PaymentGateway gateway = org.mockito.Mockito.mock(PaymentGateway.class);

    /** 실제 PG 취소가 몇 번 나갔는지 — 이중환불이면 2 이상. */
    final AtomicInteger cancelCount = new AtomicInteger(0);

    private static final int AMOUNT = 100_000;
    private static final int THREADS = 8;

    @org.junit.jupiter.api.BeforeEach
    void routeToMock() {
        org.mockito.BDDMockito.given(gateways.forOrder(org.mockito.ArgumentMatchers.any())).willReturn(gateway);
        org.mockito.BDDMockito.given(gateways.active()).willReturn(gateway);
        org.mockito.BDDMockito.given(gateway.cancel(anyString(), anyInt(), anyInt(), anyString()))
                .willAnswer(inv -> {
                    cancelCount.incrementAndGet();
                    return new PaymentGateway.CancelResult(true, "CANCELED", OffsetDateTime.now());
                });
    }

    @AfterEach
    void clean() {
        refundRepo.deleteAll();
        approvalRepo.deleteAll();
        orderRepo.deleteAll();
        enrollmentRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    @DisplayName("H-1 같은 회차를 8스레드가 동시에 환불해도 PG 취소는 1회·환불액은 승인액 1배 (이중환불 없음)")
    void concurrentRefundChargesOnce() throws Exception {
        Long roundId = seedPaidRound();

        // 8스레드를 한 지점에서 동시에 풀어(startGate) applyCancel 의 check-then-insert 창을 겹치게 한다.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    refundService.refundRoundFully(roundId, "동시성 테스트");
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    // 진 스레드 — 유니크 위반→RefundBlockedException(또는 잔액 0 no-op 후 성공). 이중환불만 아니면 정상.
                    blocked.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 스레드 종료").isTrue();
        pool.shutdownNow();

        // 핵심 불변식 — 돈은 정확히 한 번만 나간다.
        assertThat(cancelCount.get()).as("PG 취소 호출 횟수").isEqualTo(1);

        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatusOrderByIdAsc(roundId, PaymentStatus.CANCELED)
                .stream().findFirst().orElse(null);
        assertThat(order).as("전액 환불되어 CANCELED 된 주문").isNotNull();
        assertThat(order.getRefundedAmount()).as("환불 누적액 = 승인액 1배").isEqualTo(AMOUNT);

        // 원장: DONE 은 정확히 1건, REQUESTED 잔존 0 (진 스레드 시도는 롤백되어 흔적 없음).
        assertThat(refundRepo.findAll().stream().filter(r -> r.getStatus() == RefundStatus.DONE).count())
                .as("DONE 환불 이력").isEqualTo(1);
        assertThat(refundRepo.findByPaymentOrderIdAndStatus(order.getId(), RefundStatus.REQUESTED))
                .as("결과 미확인 잔존 없음").isEmpty();
        assertThat(succeeded.get()).as("환불을 실제로 성사시킨 스레드는 정확히 1개").isEqualTo(1);
        assertThat(blocked.get()).isEqualTo(THREADS - 1);
    }

    /** DONE 결제주문 1건이 달린 CONFIRMED 회차 하나를 만들고 그 roundId 를 돌려준다. */
    private Long seedPaidRound() {
        Account stu = accountRepo.save(Account.builder().email("h1@pd.com").password("x").nickName("동시성학생")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Account ins = accountRepo.save(Account.builder().email("h1i@pd.com").password("x").nickName("동시성강사")
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
        Course course = Course.builder().instructor(ins).title("동시성 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(AMOUNT).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);

        EnrollmentRound r = EnrollmentRound.builder()
                .roundIndex(1).roundKind(RoundKind.REGULAR).status(EnrollmentStatus.CONFIRMED)
                .date(LocalDate.now().plusDays(5)).blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .venueRefId("CUSTOM:1").respondedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .entrySnapshot(0).equipmentSnapshot(0).extraSnapshot(0).build();
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(AMOUNT)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r);
        enrollmentRepo.save(e);

        orderRepo.save(PaymentOrder.builder()
                .orderId("h1-ord-1").enrollmentRound(r).amount(AMOUNT).orderName("결제")
                .status(PaymentStatus.DONE).paymentKey("h1-pk-1")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return r.getId();
    }
}
