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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불 계산 정책 검증(순수 단위 — Spring 불필요). {@code @DisplayName} 위→아래 = 환불 정책 사양.
 * 정원/세션 등 외부 없이 객체만 구성. 수강료 300,000 / 정규 3회차 → 회차당 100,000.
 */
class RefundCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final OffsetDateTime NOW = TODAY.atTime(LocalTime.NOON).atOffset(ZoneOffset.UTC);
    private final RefundCalculator calc = new RefundCalculator();

    private Course course3Regular() {
        Course c = Course.builder().title("3회차 과정").price(300000).build();
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(2).build());
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(3).build());
        return c;
    }

    private Course course1Regular() {
        Course c = Course.builder().title("1회차 과정").price(150000).build();
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        return c;
    }

    // committedAt = 그레이스 폴백(createdAt)로 들어간다 — 이제 그레이스 1차 기준은 paidAtByRoundId(결제완료 시각).
    // id 를 박아 그 map 을 회차별로 지정할 수 있게 한다(순수 단위라 영속 id 가 없어 수동 부여).
    private EnrollmentRound round(int idx, EnrollmentStatus status, LocalDate date,
                                  OffsetDateTime committedAt, boolean done, int entry, int equip) {
        return EnrollmentRound.builder()
                .id((long) idx)
                .roundIndex(idx).roundKind(RoundKind.REGULAR).status(status).date(date)
                .respondedAt(committedAt).createdAt(committedAt)
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

        RefundQuote q = calc.quote(e, TODAY, NOW, Map.of());

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
        assertThat(calc.quote(dayBefore, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isEqualTo(60000);

        // 2일전(D+2) 70%: 120000*70% = 84000
        Enrollment twoBefore = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY.plusDays(2), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(twoBefore, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isEqualTo(84000);

        // 당일(D) 0%
        Enrollment sameDay = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY, NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(sameDay, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isZero();
    }

    @Test
    @DisplayName("F3 결제완료 1시간 내 취소는 날짜 무관 100% (그레이스 기준 = 결제완료 paidAt, 회차 createdAt 아님)")
    void graceFromPaidAt() {
        // 당일 세션 + createdAt 은 이틀 전(그레이스 만료)인데, 결제완료(paidAt)가 30분 전이면 → 100%.
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY, NOW.minusDays(2), false, 15000, 5000));
        Map<Long, OffsetDateTime> paidAt = Map.of(1L, NOW.minusMinutes(30));
        assertThat(calc.quote(e, TODAY, NOW, paidAt).getLines().get(0).getAmount()).isEqualTo(120000);
    }

    @Test
    @DisplayName("F4 미결제(PENDING) 배정 회차는 부대 미납이라 수강료 몫만 환불")
    void unpaidScheduledRefundsTuitionOnly() {
        // PENDING(미결제), 3일전+ → 수강료 몫 100000*100%만(부대 미납 0)
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.PENDING, TODAY.plusDays(5), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(e, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isEqualTo(100000);
    }

    @Test
    @DisplayName("F4-1 결제완료·강사 확인 전(ACCEPT_PENDING) 회차는 선결제라 부대도 이미 낸 것 → 수강료+부대 환불")
    void acceptPendingRefundsExtrasToo() {
        // 선결제: 확정(CONFIRMED) 전이라도 결제는 끝났다 → 부대 20,000 도 환불 대상. 3일전+ 100%
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.ACCEPT_PENDING, TODAY.plusDays(5), NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(e, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isEqualTo(120000);
    }

    @Test
    @DisplayName("F5 약관 예시1 — 3회차(강의료30만·입장료1만/회차): 1회차 수강+2일전 취소 = 177,000원")
    void policyExample1() {
        // 1회차 수강 완료(0) / 2회차 일정 예약·입장료 1만 결제·2일전 70% → (100000+10000)*70%=77000 / 3회차 미예약 → 100000
        EnrollmentRound done1 = round(1, EnrollmentStatus.CONFIRMED, TODAY.minusDays(1), NOW.minusDays(5), true, 10000, 0);
        EnrollmentRound booked2 = round(2, EnrollmentStatus.CONFIRMED, TODAY.plusDays(2), NOW.minusDays(2), false, 10000, 0);
        Enrollment e = enrollment(course3Regular(), 300000, done1, booked2); // 3회차는 미예약

        RefundQuote q = calc.quote(e, TODAY, NOW, Map.of());

        assertThat(q.getLines().get(0).getAmount()).isZero();             // 1회차 완료
        assertThat(q.getLines().get(1).getAmount()).isEqualTo(77000);    // 2회차 (100000+10000)×70%
        assertThat(q.getLines().get(2).getAmount()).isEqualTo(100000);   // 3회차 미예약 강의료 몫 100%
        assertThat(q.getTotal()).isEqualTo(177000);
    }

    @Test
    @DisplayName("F6 약관 예시2 — 1회차(강의료15만·입장료2만): 전날 취소 = 85,000원")
    void policyExample2() {
        // 전날(D+1) 50%, 입장료 2만 결제 → (150000+20000)*50% = 85000
        EnrollmentRound r1 = round(1, EnrollmentStatus.CONFIRMED, TODAY.plusDays(1), NOW.minusDays(2), false, 20000, 0);
        Enrollment e = enrollment(course1Regular(), 150000, r1);

        RefundQuote q = calc.quote(e, TODAY, NOW, Map.of());

        assertThat(q.getLines().get(0).getAmount()).isEqualTo(85000);    // (150000+20000)×50%
        assertThat(q.getTotal()).isEqualTo(85000);
    }

    @Test
    @DisplayName("N1 강사 미수락(ACCEPT_PENDING)은 당일이어도 100% — 날짜 페널티는 CONFIRMED(풀 예약)에만")
    void acceptPendingNoDatePenalty() {
        // 같은 당일 세션이라도 CONFIRMED 면 0%(F2)지만, 미수락이면 페널티 명분이 없어 100%.
        // = 같은 회차를 cancel(roundId) 로 취소하면 전액환불되는 것과 일치.
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.ACCEPT_PENDING, TODAY, NOW.minusDays(2), false, 15000, 5000));
        assertThat(calc.quote(e, TODAY, NOW, Map.of()).getLines().get(0).getAmount()).isEqualTo(120000);
    }

    @Test
    @DisplayName("N2 그레이스는 respondedAt 이 최근이어도 재개방 안 된다 — paidAt(결제완료)이 옛날이면 당일 0%")
    void graceNotReopenedByRespondedAt() {
        // 회차 respondedAt/createdAt 은 지금(방금 강사 수락/일정변경) 이지만, 결제완료(paidAt)는 이틀 전.
        // 옛 코드(respondedAt 기준)면 "1시간 내"로 100% 였을 것 — paidAt 기준이라 그레이스 만료 → 당일 0%.
        Enrollment e = enrollment(course3Regular(), 300000,
                round(1, EnrollmentStatus.CONFIRMED, TODAY, NOW, false, 15000, 5000));
        Map<Long, OffsetDateTime> paidAt = Map.of(1L, NOW.minusDays(2));
        assertThat(calc.quote(e, TODAY, NOW, paidAt).getLines().get(0).getAmount()).isZero();
    }

    @Test
    @DisplayName("N3 정수 나눗셈 나머지는 마지막 회차로 — 환불 합계 = 원금(버려서 사업자에 남기지 않음)")
    void remainderGoesToLastRound() {
        // 수강료 100,000 / 3회차 = 33,333 (나머지 1원). 3회차 전부 미배정 100% → 합계 100,000(=원금), 99,999 아님.
        Enrollment e = enrollment(course3Regular(), 100000); // 회차 없음 → 정규 3개 전부 미배정
        RefundQuote q = calc.quote(e, TODAY, NOW, Map.of());
        assertThat(q.getLines()).hasSize(3);
        assertThat(q.getLines().get(0).getAmount()).isEqualTo(33333);
        assertThat(q.getLines().get(1).getAmount()).isEqualTo(33333);
        assertThat(q.getLines().get(2).getAmount()).isEqualTo(33334); // 마지막 회차가 나머지 1원 흡수
        assertThat(q.getTotal()).isEqualTo(100000);
    }
}
