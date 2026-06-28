package com.diving.pungdong.payment;

import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.payment.dto.RefundQuote;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 *   <li>일정 잡힌(배정) 회차 취소 — <b>(수강료/N + 부대) × 환불율</b>. 부대는 결제 완료(CONFIRMED)분만.</li>
 *   <li>EXTRA — 수강료 몫 없음(정규 다 들음). 부대만 × 환불율.</li>
 * </ul>
 *
 * <p>환불율(세션일까지 남은 일수): <b>당일 0 / 전날 50 / 2일전 70 / 3일전+ 100</b>, 단 <b>신청 1시간 내</b> 취소는
 * 날짜 무관 100. 수강료는 1회차에 전액 냈으므로 정규회차 몫(수강료/N)은 미배정·배정 모두 여기서 계산(실행 시 1회차
 * 주문 부분취소). ⚠️ 환불율 상수는 추후 SiteSettings(런타임) 이전 후보(지금은 코드 상수).
 */
@Component
public class RefundCalculator {

    private static final int GRACE_HOURS = 1;
    private static final int SAME_DAY_PCT = 0;
    private static final int ONE_DAY_PCT = 50;
    private static final int TWO_DAY_PCT = 70;
    private static final int THREE_DAY_PLUS_PCT = 100;

    public RefundQuote quote(Enrollment enrollment, LocalDate today, LocalDateTime now) {
        int totalRegular = enrollment.getCourse() == null ? 0 : (int) enrollment.getCourse().getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR).count();
        int tuition = enrollment.getTuitionSnapshot();
        int tuitionPerRound = totalRegular == 0 ? 0 : tuition / totalRegular;

        List<RefundQuote.Line> lines = new ArrayList<>();
        int total = 0;

        // 정규 회차 1..N
        for (int idx = 1; idx <= totalRegular; idx++) {
            EnrollmentRound r = currentRegularRound(enrollment, idx);
            if (r != null && r.isDone()) {
                lines.add(new RefundQuote.Line(idx, r.getId(), 0, 0, 0, "수강 완료"));
                continue;
            }
            if (r == null) {
                // 미배정 — 수강료 몫만 100% (부대·패널티 없음)
                lines.add(new RefundQuote.Line(idx, null, tuitionPerRound, 0, 100, "미배정 수강료"));
                total += tuitionPerRound;
                continue;
            }
            int rate = ratePct(r, today, now);
            int tuitionPart = tuitionPerRound * rate / 100;
            int extraPart = paidExtras(r) * rate / 100;
            lines.add(new RefundQuote.Line(idx, r.getId(), tuitionPart, extraPart, rate, "배정취소(" + rate + "%)"));
            total += tuitionPart + extraPart;
        }

        // EXTRA 회차 — 수강료 몫 없음, 결제완료 부대만 × 환불율
        for (EnrollmentRound r : enrollment.getRounds()) {
            if (r.getRoundKind() != RoundKind.EXTRA || !r.getStatus().isActive() || r.isDone()) {
                continue;
            }
            int rate = ratePct(r, today, now);
            int extraPart = paidExtras(r) * rate / 100;
            lines.add(new RefundQuote.Line(null, r.getId(), 0, extraPart, rate, "추가세션 취소(" + rate + "%)"));
            total += extraPart;
        }

        return new RefundQuote(total, lines);
    }

    /** 부대비용은 결제 완료(CONFIRMED)분만 환불 — 미결제(PENDING/PAYMENT_PENDING)는 낸 게 없음. */
    private int paidExtras(EnrollmentRound r) {
        return r.getStatus() == EnrollmentStatus.CONFIRMED ? r.extrasTotal() : 0;
    }

    /** 그 정규 회차 idx 의 현재 회차(활성 또는 완료). 거절/취소만 있으면(또는 없으면) 미배정. */
    private EnrollmentRound currentRegularRound(Enrollment enrollment, int idx) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && Objects.equals(r.getRoundIndex(), idx))
                .filter(r -> r.getStatus().isActive() || r.isDone())
                .findFirst().orElse(null);
    }

    /** 환불율(%) — 신청 1h 내 100, 아니면 세션일까지 남은 일수로(당일0/전날50/2일전70/3일전+100). */
    private int ratePct(EnrollmentRound r, LocalDate today, LocalDateTime now) {
        LocalDateTime committedAt = r.getRespondedAt() != null ? r.getRespondedAt() : r.getCreatedAt();
        if (committedAt != null && committedAt.isAfter(now.minusHours(GRACE_HOURS))) {
            return 100; // 신청 1시간 내 무조건 100
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
