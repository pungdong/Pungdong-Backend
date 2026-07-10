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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 환불 use-case — 수강 종료(남은 회차 환불). 실 H2 + 시큐리티 + 실 서비스, 토스는 stub(즉시 CANCELED). 수강료가
 * 1회차 주문에 전액 있으므로 <b>2회차 수강료 몫도 1회차 주문 부분취소</b>로 빠지는 게 핵심.
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
}
