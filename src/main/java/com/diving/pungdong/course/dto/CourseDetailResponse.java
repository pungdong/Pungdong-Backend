package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.*;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공개 강의 상세 — 둘러보기 카드 → 상세(OPEN 코스 누구나). 강사 편집용 {@link CourseResponse}(원본
 * ticketRef·daypart 만) 와 달리, venue 를 <b>합성</b>한다: 위치 이름·type·주소(area), <b>입장료(이용권 ×
 * 평일/주말 daypart fee)</b>, 위치별 장비. 정확 결제(입장료·장비)는 부킹 시점이라 상세는 표시/안내용.
 *
 * <p>입장료는 시안의 단일 {@code entry} 가 아니라 — 코스가 그 위치에서 고른 이용권의 {@code daypart} 별
 * fee(`VenueDaypart.fee`). "일반권 (3시간) · 평일 48,000 / 주말 55,000" 식.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CourseDetailResponse {

    private Long id;
    private String title;
    private CourseKind kind;
    private String organizationCode;
    private Set<CertLevel> levels;
    @JsonProperty("isPackage")
    private boolean isPackage;
    private String disciplineCode;
    private int totalRounds;
    /** 수강료(원). 입장료·장비는 회차별 변동이라 별도(부킹 시점). */
    private int price;
    private String description;
    /** 데모(샘플) 코스 — FE 가 "샘플용" 표시 + 신청 유도 문구 분기. */
    private boolean seeded;
    private List<Media> media;

    /** 강사 — 이름만. 경력·자격·평점은 강사 프로필/리뷰 통합 후속. */
    private Long instructorId;
    private String instructorName;

    private List<Round> rounds;
    /** 진행 위치 — 회차 가로질러 dedupe + venue 합성(입장료·장비). */
    private List<Venue> venues;

    public static CourseDetailResponse from(Course c,
                                            Map<String, VenueResponse> venueByRef,
                                            Map<String, VenueEquipmentResponse> equipByRef) {
        return CourseDetailResponse.builder()
                .id(c.getId())
                .title(c.getTitle())
                .kind(c.getKind())
                .organizationCode(c.getOrganizationCode())
                .levels(c.getLevels())
                .isPackage(c.isPackage())
                .disciplineCode(c.getDisciplineCode())
                .totalRounds(c.getTotalRounds())
                .price(c.getPrice())
                .description(c.getDescription())
                .seeded(c.isSeeded())
                .media(c.getMedia().stream().map(Media::from).collect(Collectors.toList()))
                .instructorId(c.getInstructor() == null ? null : c.getInstructor().getId())
                .instructorName(c.getInstructor() == null ? null : c.getInstructor().getNickName())
                .rounds(c.getRounds().stream().map(Round::from).collect(Collectors.toList()))
                .venues(buildVenues(c, venueByRef, equipByRef))
                .build();
    }

    /** 코스 위치 dedupe(등장 순서) + venue 합성. 코스가 고른 이용권만, venue ticket 매칭으로 이름+daypart fee. */
    private static List<Venue> buildVenues(Course c, Map<String, VenueResponse> venueByRef,
                                           Map<String, VenueEquipmentResponse> equipByRef) {
        // venueRefId → 코스가 그 위치에서 쓰는 ticketRef 집합(등장 순서)
        Map<String, Set<String>> ticketRefsByVenue = new LinkedHashMap<>();
        for (CourseRound r : c.getRounds()) {
            for (RoundVenue rv : r.getVenues()) {
                Set<String> set = ticketRefsByVenue.computeIfAbsent(rv.getVenueRefId(), k -> new LinkedHashSet<>());
                rv.getTickets().forEach(t -> set.add(t.getTicketRef()));
            }
        }
        List<Venue> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ticketRefsByVenue.entrySet()) {
            String ref = e.getKey();
            VenueResponse vr = venueByRef.get(ref);
            if (vr == null) {
                continue; // 위치 해석 실패 — 스킵
            }
            Map<String, VenueResponse.Ticket> vtByRef = vr.getTickets() == null ? Map.of()
                    : vr.getTickets().stream().collect(Collectors.toMap(
                            VenueResponse.Ticket::getTicketRef, t -> t, (a, b) -> a));
            List<Ticket> tickets = new ArrayList<>();
            for (String tr : e.getValue()) {
                VenueResponse.Ticket vt = vtByRef.get(tr);
                if (vt == null) {
                    continue; // venue 에 없는 이용권(예: 이사로 사라짐) — 스킵
                }
                List<Fee> fees = vt.getDayparts() == null ? List.of()
                        : vt.getDayparts().stream()
                              .map(dp -> new Fee(dp.getKind(), dp.getFee()))
                              .collect(Collectors.toList());
                tickets.add(Ticket.builder().ticketRef(tr).ticketName(vt.getName()).fees(fees).build());
            }
            out.add(Venue.builder()
                    .venueRefId(ref)
                    .name(vr.getName())
                    .type(vr.getType())
                    .area(vr.getAddress())
                    .tickets(tickets)
                    .equipment(equipByRef.get(ref))
                    .build());
        }
        return out;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Media {
        private MediaKind kind;
        private String url;
        private int sortOrder;

        static Media from(CourseMedia m) {
            return Media.builder().kind(m.getKind()).url(m.getUrl()).sortOrder(m.getSortOrder()).build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Round {
        private RoundKind roundKind;
        private Integer roundIndex;       // REGULAR 1..N, EXTRA null
        private String description;
        private Integer freeCount;        // EXTRA 전용
        private Integer perSessionPrice;  // EXTRA 전용
        private List<String> venueRefIds; // 이 회차 진행 위치(들) — venues 의 venueRefId 참조

        static Round from(CourseRound r) {
            return Round.builder()
                    .roundKind(r.getRoundKind())
                    .roundIndex(r.getRoundIndex())
                    .description(r.getDescription())
                    .freeCount(r.getFreeCount())
                    .perSessionPrice(r.getPerSessionPrice())
                    .venueRefIds(r.getVenues().stream().map(RoundVenue::getVenueRefId).collect(Collectors.toList()))
                    .build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Venue {
        private String venueRefId;
        private String name;
        private VenueType type;
        /** 도로명주소(시안의 area). */
        private String area;
        /** 코스가 쓰는 이용권 + 입장료(daypart fee). */
        private List<Ticket> tickets;
        /** 위치별 대여 장비(강사×위치 가격표 합성). 미설정이면 null. */
        private VenueEquipmentResponse equipment;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Ticket {
        private String ticketRef;
        private String ticketName;  // 이용권 이름 (예: "일반권 (3시간)")
        private List<Fee> fees;     // daypart 별 입장료
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Fee {
        private DaypartKind daypart; // WEEKDAY / WEEKEND
        private Integer fee;         // 입장료(원)
    }
}
