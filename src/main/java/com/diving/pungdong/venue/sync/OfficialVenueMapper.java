package com.diving.pungdong.venue.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.diving.pungdong.venue.ClosureType;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.dto.VenueResponse;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sanity GROQ 결과(JsonNode) → {@link VenueResponse}(scope=OFFICIAL) 매핑. custom 의
 * {@code VenueResponse.from(Venue)} 와 같은 모양을 만들어, 코스 빌더 통합 목록이 출처와 무관하게
 * 한 타입으로 보이게 한다(durationHours 파생 규칙도 동일: OPEN=키반납, FIXED=첫 블록, SAME=null).
 */
final class OfficialVenueMapper {

    private OfficialVenueMapper() {}

    static VenueResponse toResponse(SanityVenueClient.OfficialVenueDoc official) {
        JsonNode v = official.getDoc();
        return VenueResponse.builder()
                .id(null)
                .name(text(v, "name"))
                .type(VenueType.valueOf(text(v, "type")))
                .address(text(v, "address"))
                .addressDetail(text(v, "addressDetail"))
                .latitude(dbl(v, "latitude"))
                .longitude(dbl(v, "longitude"))
                .maxDepth(integer(v, "maxDepth"))
                .scope("OFFICIAL")
                .venueRefId("OFFICIAL:" + official.getId())
                .ownerId(null)
                .lockedDisciplineCode(null)
                .closures(closures(v.get("closures")))
                .tickets(tickets(v.get("tickets")))
                .build();
    }

    private static List<VenueResponse.Ticket> tickets(JsonNode arr) {
        List<VenueResponse.Ticket> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        int i = 0;
        for (JsonNode t : arr) {
            List<VenueResponse.Daypart> dayparts = new ArrayList<>();
            JsonNode weekday = t.get("weekday");
            if (weekday != null && !weekday.isNull()) {
                dayparts.add(daypart(weekday, DaypartKind.WEEKDAY));
            }
            JsonNode weekend = t.get("weekend");
            if (weekend != null && !weekend.isNull()) {
                dayparts.add(daypart(weekend, DaypartKind.WEEKEND));
            }
            out.add(VenueResponse.Ticket.builder()
                    .id(null)
                    .name(text(t, "name"))
                    .sortOrder(i++)
                    .disciplineCodes(stringSet(t.get("disciplines")))
                    .dayparts(dayparts)
                    .build());
        }
        return out;
    }

    private static VenueResponse.Daypart daypart(JsonNode d, DaypartKind kind) {
        TimeMode timeMode = d.hasNonNull("timeMode") ? TimeMode.valueOf(d.get("timeMode").asText()) : null;
        List<VenueResponse.TimeBlock> blocks = new ArrayList<>();
        JsonNode blocksNode = d.get("blocks");
        if (blocksNode != null && blocksNode.isArray()) {
            int i = 0;
            for (JsonNode b : blocksNode) {
                blocks.add(VenueResponse.TimeBlock.builder()
                        .startTime(time(b, "start"))
                        .endTime(time(b, "end"))
                        .sortOrder(i++)
                        .build());
            }
        }
        LocalTime openStart = time(d, "open");
        LocalTime openEnd = time(d, "close");
        Integer holdHours = integer(d, "holdHours");
        return VenueResponse.Daypart.builder()
                .kind(kind)
                .sold(d.path("sold").asBoolean(true))
                .fee(integer(d, "fee"))
                .timeMode(timeMode)
                .openStart(openStart)
                .openEnd(openEnd)
                .holdHours(holdHours)
                .timeBlocks(blocks)
                .durationHours(durationHours(timeMode, holdHours, blocks))
                .build();
    }

    private static Double durationHours(TimeMode timeMode, Integer holdHours, List<VenueResponse.TimeBlock> blocks) {
        if (timeMode == TimeMode.OPEN) {
            return holdHours == null ? null : (double) holdHours;
        }
        if (timeMode == TimeMode.FIXED && !blocks.isEmpty()) {
            VenueResponse.TimeBlock first = blocks.get(0);
            if (first.getStartTime() == null || first.getEndTime() == null) {
                return null;
            }
            long minutes = Duration.between(first.getStartTime(), first.getEndTime()).toMinutes();
            return Math.round(minutes / 60.0 * 10) / 10.0;
        }
        return null; // SAME 또는 블록 없음
    }

    private static List<VenueResponse.Closure> closures(JsonNode arr) {
        List<VenueResponse.Closure> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode c : arr) {
            Set<java.time.DayOfWeek> weekdays = new LinkedHashSet<>();
            JsonNode wd = c.get("weekdays");
            if (wd != null && wd.isArray()) {
                for (JsonNode day : wd) {
                    weekdays.add(java.time.DayOfWeek.valueOf(day.asText()));
                }
            }
            out.add(VenueResponse.Closure.builder()
                    .type(c.hasNonNull("type") ? ClosureType.valueOf(c.get("type").asText()) : null)
                    .weekdays(weekdays)
                    .nth(integer(c, "nth"))
                    .monthlyWeekday(c.hasNonNull("monthlyWeekday") ? java.time.DayOfWeek.valueOf(c.get("monthlyWeekday").asText()) : null)
                    .build());
        }
        return out;
    }

    private static Set<String> stringSet(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static Double dbl(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asDouble() : null;
    }

    private static Integer integer(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asInt() : null;
    }

    private static LocalTime time(JsonNode n, String field) {
        return n.hasNonNull(field) ? LocalTime.parse(n.get(field).asText()) : null;
    }
}
