package com.diving.pungdong.payment;

import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.dto.RefundQuote;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 환불 계산 — 수강 종료(남은 회차 환불)의 회차별 환불액. 정책(docs/features/booking.md·payment.md):
 *
 * <ul>
 *   <li>수강 완료(done) 회차 — <b>0</b>(이미 들음).</li>
 *   <li>아직 일정 안 잡은 정규회차 — <b>수강료/N × 100%</b>(부대 0, 패널티 0 — 약속·지출 없음).</li>
 *   <li>강사 <b>미수락</b>(ACCEPT_PENDING)·미결제(PENDING) 배정회차 취소 — <b>(수강료/N + 부대) × 100%</b>.
 *       날짜 페널티 없음 — 페널티는 "강사가 풀을 예약한 뒤 코앞 취소" 손해를 물리는 것이라 <b>미수락 = 풀 미예약 = 명분 없음</b>.
 *       (같은 회차를 {@code cancel(roundId)} 로 취소하면 100% 전액환불되는 것과 일치시킨다.)</li>
 *   <li><b>확정(CONFIRMED)</b> 배정회차 취소 — <b>(수강료/N + 부대) × 환불율</b>. 날짜 페널티는 여기서만.</li>
 *   <li>EXTRA — 수강료 몫 없음(정규 다 들음). 부대만 × (CONFIRMED면 환불율, 미수락이면 100%).</li>
 * </ul>
 *
 * <p>환불율(세션일까지 남은 일수, <b>CONFIRMED 한정</b>): <b>당일 0 / 전날 50 / 2일전 70 / 3일전+ 100</b>, 단
 * <b>강사 수락(확정) 1시간 내</b>(그레이스) 취소는 날짜 무관 100. 그레이스 기준은 <b>확정 시각</b>(CONFIRMED 회차의
 * {@code respondedAt} = 수락·pick-slot·재수락 순간). 페널티 명분이 "강사가 수락 후 풀을 예약했다"이므로 시계도 수락에서
 * 돈다 — 결제 시각 기준(#258)은 강사가 늦게 수락할수록 학생이 확정 직후 취소해도 페널티를 맞는 소비자 기만이었다.
 * 강사의 늦은 수락은 학생의 무료취소 구간을 늘릴 뿐이고(미수락 = 100%), 확정 뒤 취소할 사람은 그 1시간에 하면 된다.
 * 수강료는 1회차에 전액 냈으므로 정규회차 몫(수강료/N)은 미배정·배정 모두 여기서 계산. 나눗셈 나머지는 <b>마지막 정규회차</b>에
 * 얹어 환불 합계가 원금과 어긋나지 않게 한다(버려서 사업자에 남기지 않음). ⚠️ 환불율 상수는 추후 SiteSettings 이전 후보.
 */
@Component
public class RefundCalculator {

    private static final int GRACE_HOURS = 1;
    private static final int SAME_DAY_PCT = 0;
    private static final int ONE_DAY_PCT = 50;
    private static final int TWO_DAY_PCT = 70;
    private static final int THREE_DAY_PLUS_PCT = 100;

    public RefundQuote quote(Enrollment enrollment, LocalDate today, OffsetDateTime now) {
        int totalRegular = enrollment.getCourse() == null ? 0 : (int) enrollment.getCourse().getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR).count();
        int tuition = enrollment.getTuitionSnapshot();
        int tuitionPerRound = totalRegular == 0 ? 0 : tuition / totalRegular;
        // 정수 나눗셈 나머지(0..N-1원)는 마지막 정규회차 몫에 얹는다 — 버리면 그만큼 환불 합계 < 원금(학생 불리, 대사 어긋남).
        int tuitionRemainder = tuition - tuitionPerRound * totalRegular;

        List<RefundQuote.Line> lines = new ArrayList<>();
        int total = 0;

        // 정규 회차 1..N
        for (int idx = 1; idx <= totalRegular; idx++) {
            int tuitionBase = tuitionPerRound + (idx == totalRegular ? tuitionRemainder : 0);
            EnrollmentRound r = currentRegularRound(enrollment, idx);
            if (r != null && r.isDone()) {
                lines.add(new RefundQuote.Line(idx, r.getId(), 0, 0, 0, "수강 완료"));
                continue;
            }
            if (r == null) {
                // 미배정 — 수강료 몫만 100% (부대·패널티 없음)
                lines.add(new RefundQuote.Line(idx, null, tuitionBase, 0, 100, "미배정 수강료"));
                total += tuitionBase;
                continue;
            }
            // 날짜 페널티는 CONFIRMED(강사 수락 = 풀 예약)에만. 미수락(ACCEPT_PENDING)·미결제(PENDING)는 100%.
            int rate = r.getStatus() == EnrollmentStatus.CONFIRMED ? ratePct(r, today, now) : 100;
            int tuitionPart = tuitionBase * rate / 100;
            int extraPart = paidExtras(r) * rate / 100;
            lines.add(new RefundQuote.Line(idx, r.getId(), tuitionPart, extraPart, rate, "배정취소(" + rate + "%)"));
            total += tuitionPart + extraPart;
        }

        // EXTRA 회차 — 수강료 몫 없음, 결제완료 부대만 × (CONFIRMED면 환불율, 미수락이면 100%)
        for (EnrollmentRound r : enrollment.getRounds()) {
            if (r.getRoundKind() != RoundKind.EXTRA || !r.getStatus().isActive() || r.isDone()) {
                continue;
            }
            int rate = r.getStatus() == EnrollmentStatus.CONFIRMED ? ratePct(r, today, now) : 100;
            int extraPart = paidExtras(r) * rate / 100;
            lines.add(new RefundQuote.Line(null, r.getId(), 0, extraPart, rate, "추가세션 취소(" + rate + "%)"));
            total += extraPart;
        }

        return new RefundQuote(total, lines);
    }

    /**
     * 부대비용은 <b>결제된</b> 회차만 환불 — 미결제(PENDING)는 낸 게 없다. 선결제라 결제 시점이 강사 수락보다
     * 앞이므로 확정 전(ACCEPT_PENDING)도 이미 낸 상태 = 환불 대상이다.
     */
    private int paidExtras(EnrollmentRound r) {
        return r.getStatus() == EnrollmentStatus.CONFIRMED
                || r.getStatus() == EnrollmentStatus.ACCEPT_PENDING ? r.extrasTotal() : 0;
    }

    /** 그 정규 회차 idx 의 현재 회차(활성 또는 완료). 거절/취소만 있으면(또는 없으면) 미배정. */
    private EnrollmentRound currentRegularRound(Enrollment enrollment, int idx) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && Objects.equals(r.getRoundIndex(), idx))
                .filter(r -> r.getStatus().isActive() || r.isDone())
                .findFirst().orElse(null);
    }

    /**
     * 환불율(%) — <b>강사 수락(확정) 1h 내</b>(그레이스) 100, 아니면 세션일까지 남은 일수로(당일0/전날50/2일전70/3일전+100).
     * CONFIRMED 회차에만 불린다. 그레이스 앵커 = CONFIRMED 회차의 {@code respondedAt}: 수락(accept)·학생 pick-slot·
     * 일정변경 재수락 모두 "확정된 순간"에 찍힌다. 확정 후엔 respondedAt 을 갱신하는 경로가 없어(제안·재요청은
     * ACCEPT_PENDING 으로 되돌아간 뒤에만 가능, 그 상태는 100%) 그레이스가 조용히 재개방되지 않는다.
     * respondedAt 이 없으면(legacy) 회차 createdAt 으로 보수적 폴백.
     */
    private int ratePct(EnrollmentRound r, LocalDate today, OffsetDateTime now) {
        OffsetDateTime confirmedAt = r.getRespondedAt() != null ? r.getRespondedAt() : r.getCreatedAt();
        if (confirmedAt != null && confirmedAt.isAfter(now.minusHours(GRACE_HOURS))) {
            return 100; // 강사 수락 1시간 내 무조건 100
        }
        if (r.getDate() == null) {
            return THREE_DAY_PLUS_PCT;
        }
        long daysUntil = ChronoUnit.DAYS.between(today, r.getDate());
        if (daysUntil <= 0) {
            return SAME_DAY_PCT;
        }
        if (daysUntil == 1) {
            return ONE_DAY_PCT;
        }
        if (daysUntil == 2) {
            return TWO_DAY_PCT;
        }
        return THREE_DAY_PLUS_PCT;
    }
}
