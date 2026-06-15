package com.diving.pungdong.enrollment;

import com.diving.pungdong.venue.ClosureType;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.dto.VenueResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * venue 운영 시간블록 도출 — 한 위치({@link VenueResponse})·이용권·날짜에 대해 그 날의 <b>운영 시간블록 +
 * 입장료</b>를 계산한다. 교집합(강사 availability ∩ venue 운영시간)의 venue-쪽 절반. 옵션 조회와 신청 검증
 * 양쪽이 같은 규칙을 쓰도록 단일 출처.
 *
 * <p>scope-무관 — {@code VenueResponse}(CUSTOM=DB, OFFICIAL=Sanity 캐시 둘 다 평탄화됨)만 본다.
 *
 * <p>v1 범위: 평일/주말 daypart, {@code FIXED}(timeBlocks)·{@code OPEN}(단일 블록)·{@code SAME}(주말=평일 구성,
 * 주말 fee) 지원. 휴무는 {@code WEEKLY}·{@code MONTHLY}(N째 주 요일) 모두 날짜 제외.
 */
@Component
public class BookableSlotDeriver {

    /** 한 운영 블록 = 시간범위 + 그 날짜의 입장료. */
    public static final class Block {
        private final LocalTime start;
        private final LocalTime end;
        private final int fee;

        public Block(LocalTime start, LocalTime end, int fee) {
            this.start = start;
            this.end = end;
            this.fee = fee;
        }

        public LocalTime getStart() { return start; }
        public LocalTime getEnd() { return end; }
        public int getFee() { return fee; }

        public boolean sameTime(LocalTime s, LocalTime e) {
            return start.equals(s) && end.equals(e);
        }
    }

    /** 그 위치·이용권의 {@code date} 운영 블록들(+입장료). 휴무일이거나 미판매면 빈 리스트. */
    public List<Block> blocksFor(VenueResponse venue, String ticketRef, LocalDate date) {
        if (venue == null || !StringUtils.hasText(ticketRef) || date == null) {
            return List.of();
        }
        if (isClosed(venue, date)) {
            return List.of();
        }
        VenueResponse.Ticket ticket = findTicket(venue, ticketRef);
        if (ticket == null) {
            return List.of();
        }
        DaypartKind kind = isWeekend(date) ? DaypartKind.WEEKEND : DaypartKind.WEEKDAY;
        VenueResponse.Daypart dp = pickDaypart(ticket, kind);
        if (dp == null || !dp.isSold()) {
            return List.of();
        }
        int fee = dp.getFee() == null ? 0 : dp.getFee();

        List<VenueResponse.TimeBlock> raw;
        if (dp.getTimeMode() == TimeMode.OPEN) {
            raw = dp.getOpenStart() == null || dp.getOpenEnd() == null ? List.of()
                    : List.of(timeBlock(dp.getOpenStart(), dp.getOpenEnd()));
        } else if (dp.getTimeMode() == TimeMode.SAME) {
            // 주말이 평일 구성을 그대로 따른다 — 블록은 평일 daypart, fee 는 주말 daypart.
            VenueResponse.Daypart weekday = pickDaypart(ticket, DaypartKind.WEEKDAY);
            raw = weekday == null ? List.of() : blocksOf(weekday);
        } else { // FIXED
            raw = blocksOf(dp);
        }

        List<Block> out = new ArrayList<>();
        for (VenueResponse.TimeBlock b : raw) {
            if (b.getStartTime() != null && b.getEndTime() != null && b.getEndTime().isAfter(b.getStartTime())) {
                out.add(new Block(b.getStartTime(), b.getEndTime(), fee));
            }
        }
        return out;
    }

    private List<VenueResponse.TimeBlock> blocksOf(VenueResponse.Daypart dp) {
        if (dp.getTimeMode() == TimeMode.OPEN) {
            return dp.getOpenStart() == null || dp.getOpenEnd() == null ? List.of()
                    : List.of(timeBlock(dp.getOpenStart(), dp.getOpenEnd()));
        }
        return dp.getTimeBlocks() == null ? List.of() : dp.getTimeBlocks();
    }

    private VenueResponse.TimeBlock timeBlock(LocalTime s, LocalTime e) {
        return VenueResponse.TimeBlock.builder().startTime(s).endTime(e).build();
    }

    private VenueResponse.Ticket findTicket(VenueResponse venue, String ticketRef) {
        if (venue.getTickets() == null) {
            return null;
        }
        return venue.getTickets().stream()
                .filter(t -> ticketRef.equals(t.getTicketRef()))
                .findFirst().orElse(null);
    }

    private VenueResponse.Daypart pickDaypart(VenueResponse.Ticket ticket, DaypartKind kind) {
        if (ticket.getDayparts() == null) {
            return null;
        }
        VenueResponse.Daypart match = ticket.getDayparts().stream()
                .filter(d -> d.getKind() == kind).findFirst().orElse(null);
        if (match != null && match.isSold()) {
            return match;
        }
        // 주말 미판매/부재 → 평일 구성으로 폴백(흔한 운영: 주말 동일).
        if (kind == DaypartKind.WEEKEND) {
            return ticket.getDayparts().stream()
                    .filter(d -> d.getKind() == DaypartKind.WEEKDAY).findFirst().orElse(null);
        }
        return match;
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    private boolean isClosed(VenueResponse venue, LocalDate date) {
        if (venue.getClosures() == null) {
            return false;
        }
        for (VenueResponse.Closure c : venue.getClosures()) {
            if (c.getType() == ClosureType.WEEKLY) {
                if (c.getWeekdays() != null && c.getWeekdays().contains(date.getDayOfWeek())) {
                    return true;
                }
            } else if (c.getType() == ClosureType.MONTHLY) {
                if (c.getNth() != null && c.getMonthlyWeekday() != null
                        && date.equals(nthWeekdayOfMonth(date, c.getNth(), c.getMonthlyWeekday()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 그 달의 N째 주 X요일 날짜(없으면 null). */
    private LocalDate nthWeekdayOfMonth(LocalDate inMonth, int nth, DayOfWeek weekday) {
        LocalDate first = inMonth.withDayOfMonth(1)
                .with(TemporalAdjusters.firstInMonth(weekday));
        LocalDate target = first.plusWeeks(nth - 1L);
        return target.getMonth() == inMonth.getMonth() ? target : null;
    }
}
