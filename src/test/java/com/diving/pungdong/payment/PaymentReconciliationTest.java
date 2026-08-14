package com.diving.pungdong.payment;

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
import com.diving.pungdong.enrollment.EnrollmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 표면화 사양 — 결과 미확인 시도(승인 ATTEMPTED / 환불 REQUESTED)가 오래 남으면 세어 알린다.
 *
 * <p><b>왜 돈이 걸리나</b>: 이 행들은 "카드가 청구/취소됐는지 모르는" 상태라 사람이 PG 원장과 대사해야 하는데,
 * 지금까지 그런 행이 있다는 걸 자동으로 볼 방법이 없었다. 이 스윕이 그 공백을 메운다(탐지만 — 자동 재시도 X).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. RC* 대사.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentReconciliationTest {

    @Autowired
    private PaymentReconciliation reconciliation;
    @Autowired
    private PaymentApprovalJpaRepo approvalRepo;
    @Autowired
    private RefundOrderJpaRepo refundRepo;
    @Autowired
    private PaymentOrderJpaRepo orderRepo;
    @Autowired
    private EnrollmentJpaRepo enrollmentRepo;
    @Autowired
    private CourseJpaRepo courseRepo;
    @Autowired
    private AccountJpaRepo accountRepo;

    @AfterEach
    void cleanup() {
        refundRepo.deleteAll();
        approvalRepo.deleteAll();
        orderRepo.deleteAll();
        enrollmentRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @Test
    @DisplayName("RC1 유예 시간 넘게 미확정인 승인/환불 시도만 센다 — 방금 시작한 정상 시도는 오탐하지 않는다")
    void reportsOnlyStaleUnresolvedAttempts() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 오래된 미확정 — 대사 대상
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.ATTEMPTED)
                .attemptedAt(now.minusMinutes(20)).build());
        refundRepo.save(RefundOrder.builder()
                .amount(10000).reason("이전 시도").status(RefundStatus.REQUESTED)
                .createdAt(now.minusMinutes(20)).build());
        // 방금 시작한 미확정 — 곧 확정될 정상 시도라 세면 안 됨
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.ATTEMPTED)
                .attemptedAt(now).build());
        // 이미 확정된 시도 — 대사 대상 아님
        approvalRepo.save(PaymentApproval.builder()
                .amount(30000).provider(PaymentProvider.INICIS).status(ApprovalStatus.APPROVED)
                .attemptedAt(now.minusMinutes(20)).resolvedAt(now.minusMinutes(19)).build());

        int stuck = reconciliation.reportStuck(now, 15);

        assertThat(stuck).isEqualTo(2); // 오래된 ATTEMPTED 1 + 오래된 REQUESTED 1
    }

    @Test
    @DisplayName("RC2 회차 순액 == chargeTotal 이면 금액 대사에 안 걸린다(정합)")
    void matchedAmountNotFlagged() {
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20);
        // chargeTotal = 100,000(수강료) + 20,000(입장료) = 120,000, 순액 = 120,000 − 0 = 120,000
        paidRound("rc2@pd.com", 100000, 20000, 120000, 0, old);
        assertThat(reconciliation.reportAmountMismatch(OffsetDateTime.now(ZoneOffset.UTC), 15)).isZero();
    }

    @Test
    @DisplayName("RC3 회차 순액 ≠ chargeTotal 이면 드리프트로 표면화(환불은 나갔는데 청구액이 안 줄어든 등)")
    void mismatchedAmountFlagged() {
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20);
        // chargeTotal 120,000 인데 20,000 이 환불돼 순액 100,000 → 정상 부분환불이면 chargeTotal 도 줄었어야 = 드리프트
        paidRound("rc3@pd.com", 100000, 20000, 120000, 20000, old);
        assertThat(reconciliation.reportAmountMismatch(OffsetDateTime.now(ZoneOffset.UTC), 15)).isEqualTo(1);
    }

    @Test
    @DisplayName("RC4 결과 미확인 환불(REQUESTED)이 걸린 회차는 전이 중이라 금액 대사에서 제외(오탐 방지)")
    void inFlightRefundSkipped() {
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20);
        EnrollmentRound r = paidRound("rc4@pd.com", 100000, 20000, 120000, 20000, old); // 순액 100,000 ≠ 120,000
        PaymentOrder order = orderRepo.findByEnrollmentRoundIdAndStatusOrderByIdAsc(r.getId(), PaymentStatus.DONE).get(0);
        refundRepo.save(RefundOrder.builder().paymentOrder(order).amount(20000).reason("진행 중")
                .status(RefundStatus.REQUESTED).createdAt(old).build());
        // 어긋나 보여도 REQUESTED 가 걸려 있으니 전이 중으로 보고 제외(그건 reportStuck 이 표면화).
        assertThat(reconciliation.reportAmountMismatch(OffsetDateTime.now(ZoneOffset.UTC), 15)).isZero();
    }

    /** 결제완료(CONFIRMED) 회차 1개 + DONE 주문 1개. chargeTotal = tuition + entry, 순액 = orderAmount − refunded. */
    private EnrollmentRound paidRound(String email, int tuition, int entry, int orderAmount, int refunded, OffsetDateTime at) {
        Account stu = accountRepo.save(Account.builder().email(email).password("x").nickName(email)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        Course course = Course.builder().title("과정").kind(CourseKind.CERTIFICATION)
                .organizationCode("AIDA").disciplineCode("FREEDIVING").totalRounds(1).price(tuition)
                .status(CourseStatus.OPEN).createdAt(at).build();
        course.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        courseRepo.save(course);
        EnrollmentRound r = EnrollmentRound.builder()
                .roundIndex(1).roundKind(RoundKind.REGULAR).status(EnrollmentStatus.CONFIRMED)
                .date(LocalDate.now().plusDays(5)).respondedAt(at).createdAt(at)
                .entrySnapshot(entry).equipmentSnapshot(0).extraSnapshot(0).build();
        Enrollment e = Enrollment.builder().student(stu).course(course).tuitionSnapshot(tuition).createdAt(at).build();
        e.addRound(r);
        enrollmentRepo.save(e);
        orderRepo.save(PaymentOrder.builder()
                .orderId("ord-" + email).enrollmentRound(r).amount(orderAmount).orderName("결제")
                .status(PaymentStatus.DONE).paymentKey("pk-" + email).refundedAmount(refunded)
                .approvedAt(at).createdAt(at).build());
        return r;
    }
}
