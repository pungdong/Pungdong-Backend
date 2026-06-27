package com.diving.pungdong.payment;

import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.dto.RefundQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불 계산 정책 검증(순수 단위 — Spring 불필요). {@code @DisplayName} 위→아래 = 환불 정책 사양.
 * 정원/세션 등 외부 없이 객체만 구성. 수강료 300,000 / 정규 3회차 → 회차당 100,000.
 */
class RefundCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final LocalDateTime NOW = TODAY.atTime(LocalTime.NOON);
    private final RefundCalculator calc = new RefundCalculator();

    private Course course3Regular() {
        Course c = Course.builder().title("3회차 과정").price(300000).build();
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(2).build());
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(3).build());
        return c;
    }

    private EnrollmentRound round(int idx, EnrollmentStatus status, LocalDate date,
                                  LocalDateTime respondedAt, boolean done, int entry, int equip) {
        return EnrollmentRound.builder()
                .roundIndex(idx).roundKind(RoundKind.REGULAR).status(status).date(date)
                .respondedAt(respondedAt).createdAt(respondedAt)
                .doneAt(done ? NOW.minusDays(1) : null)
                .entrySnapshot(entry).equipmentSnapshot(equip).extraSnapshot(0).build();
    }

    private Enrollment enrollment(Course course, int tuition, EnrollmentRound... rounds) {
        Enrollment e = Enrollment.builder().course(course).tuitionSnapshot(tuition).build();
        for (EnrollmentRound r : rounds) {
            e.addRound(r);
        }
        return e;
    }

    @Test
    @DisplayName("F1 done=0 · 미배정=수강료/N(100%) · 3일전+ 배정취소=(수강료/N+부대)×100%")
    void mixedRounds() {
        EnrollmentRound r1 = round(1, EnrollmentStatus.CONFIRMED, TODAY.minusDays(3), NOW.minusDays(5), true, 15000, 5000);
        EnrollmentRound r2 = round(2, EnrollmentStatus.CONFIRMED, TODAY.plusDays(5), NOW.minusDays(2), false, 15000, 5000);
        Enrollment e = enrollment(course3Regular(), 300000, r1, r2); // 3회차는 미배정

        RefundQuote q = calc.quote(e, TODAY, NOW);

        // r1 done 0 / r2 (100000+20000)*100% / 3회차 미배정 100000
        assertThat(q.getTotal()).isEqualTo(120000 + 100000);
        assertThat(q.getLines()).hasSize(3);
        assertThat(q.getLines().get(0).getAmount()).isZero();              // 1회차 완료
        assertThat(q.getLines().get(1).getAmount()).isEqualTo(120000);    // 2회차 배정취소 100%
        assertThat(q.getLines().get(2).getAmount()).isEqualTo(100000);    // 3회차 미배정 수강료
        assertThat(q.getLines().get(2).getRoundId()).isNull();
    }

    @Test
    @DisplayName("F2 환불율 — 전날 50% / 2일전 70% / 당일 0%")
    void rateTiers() {
        // 전날(D+1) 50%: (100000+20000)*50% = 60000, 3회차 미배정 100000
        Enrollment dayBefore = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY.plusDays(1), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(dayBefore, TODAY, NOW).getLines().get(0).getAmount()).isEqualTo(60000);

        // 2일전(D+2) 70%: 120000*70% = 84000
        Enrollment twoBefore = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY.plusDays(2), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(twoBefore, TODAY, NOW).getLines().get(0).getAmount()).isEqualTo(84000);

        // 당일(D) 0%
        Enrollment sameDay = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY, NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(sameDay, TODAY, NOW).getLines().get(0).getAmount()).isZero();
    }

    @Test
    @DisplayName("F3 신청 1시간 내 취소는 날짜 무관 100% 환불")
    void graceWindow() {
        // 당일이지만 신청(respondedAt) 30분 전 → 100%
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY, NOW.minusMinutes(30), false, 15000, 5000));
        assertThat(calc.quote(e, TODAY, NOW).getLines().get(0).getAmount()).isEqualTo(120000);
    }

    @Test
    @DisplayName("F4 미결제(PENDING/PAYMENT_PENDING) 배정 회차는 부대 미납이라 수강료 몫만 환불")
    void unpaidScheduledRefundsTuitionOnly() {
        // PAYMENT_PENDING, 3일전+ → 수강료 몫 100000*100%만(부대 미납 0)
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.PAYMENT_PENDING, TODAY.plusDays(5), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(e, TODAY, NOW).getLines().get(0).getAmount()).isEqualTo(100000);
    }
}
