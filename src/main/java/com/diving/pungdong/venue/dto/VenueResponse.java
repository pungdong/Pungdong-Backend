package com.diving.pungdong.venue.dto;

import com.diving.pungdong.venue.*;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 위치 응답 (강사 커스텀). CollectionModel 키 = "venues". 코스 빌더에서 FE 가 Sanity OFFICIAL 과
 * 합쳐 통합 리스트를 만드므로, BE 응답은 {@code scope = "CUSTOM"} 으로 표시한다(FE 구분용).
 *
 * <p>이용시간({@code durationHours})은 저장값이 아니라 시간블록/키반납에서 파생 계산.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "venues")
public class VenueResponse {
    private Long id;
    private String name;
    private VenueType type;
    /** 정식 도로명주소 (위/경도 기준). */
    private String address;
    /** 세부주소 (동·호수 등, 선택). */
    private String addressDetail;
    private Double latitude;
    private Double longitude;
    /** 최대수심(m, 선택). */
    private Integer maxDepth;
    /** "CUSTOM"(BE) | "OFFICIAL"(Sanity). 코스 빌더 통합 목록에서 출처 구분. */
    private String scope;
    /**
     * 위치 참조 토큰 — 코스가 저장하는 안정 식별자. {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"}.
     * custom 은 PK 가 재사용되지 않는 한 안정, official 은 Sanity 문서 _id.
     */
    private String venueRefId;
    private Long ownerId;
    private String lockedDisciplineCode;
    private List<Closure> closures;
    private List<Ticket> tickets;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VenueResponse from(Venue v) {
        return VenueResponse.builder()
                .id(v.getId())
                .name(v.getName())
                .type(v.getType())
                .address(v.getAddress())
                .addressDetail(v.getAddressDetail())
                .latitude(v.getLatitude())
                .longitude(v.getLongitude())
                .maxDepth(v.getMaxDepth())
                .scope("CUSTOM")
                .venueRefId("CUSTOM:" + v.getId())
                .ownerId(v.getOwner() == null ? null : v.getOwner().getId())
                .lockedDisciplineCode(v.getLockedDisciplineCode())
                .closures(v.getClosures().stream().map(Closure::from).collect(Collectors.toList()))
                .tickets(v.getTickets().stream().map(Ticket::from).collect(Collectors.toList()))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Ticket {
        private Long id;
        private String name;
        private int sortOrder;
        private Set<String> disciplineCodes;
        private List<Daypart> dayparts;

        static Ticket from(VenueTicket t) {
            return Ticket.builder()
                    .id(t.getId())
                    .name(t.getName())
                    .sortOrder(t.getSortOrder())
                    .disciplineCodes(t.getDisciplineCodes())
                    .dayparts(t.getDayparts().stream().map(Daypart::from).collect(Collectors.toList()))
                    .build();
        }
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Daypart {
        private DaypartKind kind;
        private boolean sold;
        private Integer fee;
        private TimeMode timeMode;
        private LocalTime openStart;
        private LocalTime openEnd;
        private Integer holdHours;
        private List<TimeBlock> timeBlocks;
        /** 파생 이용시간(시간). FIXED=첫 블록 길이, OPEN=키반납 시간, SAME=null(평일 따름). */
        private Double durationHours;

        static Daypart from(VenueDaypart d) {
            return Daypart.builder()
                    .kind(d.getKind())
                    .sold(d.isSold())
                    .fee(d.getFee())
                    .timeMode(d.getTimeMode())
                    .openStart(d.getOpenStart())
                    .openEnd(d.getOpenEnd())
                    .holdHours(d.getHoldHours())
                    .timeBlocks(d.getTimeBlocks().stream().map(TimeBlock::from).collect(Collectors.toList()))
                    .durationHours(durationHours(d))
                    .build();
        }

        private static Double durationHours(VenueDaypart d) {
            if (d.getTimeMode() == TimeMode.OPEN) {
                return d.getHoldHours() == null ? null : (double) d.getHoldHours();
            }
            if (d.getTimeMode() == TimeMode.FIXED && !d.getTimeBlocks().isEmpty()) {
                VenueTimeBlock first = d.getTimeBlocks().get(0);
                return hoursBetween(first.getStartTime(), first.getEndTime());
            }
            return null; // SAME(주말=평일 따름) 또는 블록 없음
        }

        private static Double hoursBetween(LocalTime start, LocalTime end) {
            if (start == null || end == null) {
                return null;
            }
            long minutes = Duration.between(start, end).toMinutes();
            return Math.round(minutes / 60.0 * 10) / 10.0;
        }
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class TimeBlock {
        private LocalTime startTime;
        private LocalTime endTime;
        private int sortOrder;

        static TimeBlock from(VenueTimeBlock b) {
            return TimeBlock.builder()
                    .startTime(b.getStartTime())
                    .endTime(b.getEndTime())
                    .sortOrder(b.getSortOrder())
                    .build();
        }
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class Closure {
        private ClosureType type;
        private Set<DayOfWeek> weekdays;
        private Integer nth;
        private DayOfWeek monthlyWeekday;

        static Closure from(VenueClosure c) {
            return Closure.builder()
                    .type(c.getType())
                    .weekdays(c.getWeekdays())
                    .nth(c.getNth())
                    .monthlyWeekday(c.getMonthlyWeekday())
                    .build();
        }
    }
}
