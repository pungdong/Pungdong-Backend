package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.*;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 코스 응답(상세/목록). 위치별 대여 장비는 저장값이 아니라 강사×위치 가격표에서 <b>읽기 시점 합성</b> —
 * {@code equipmentByRef}(venueRefId → 가격표)를 서비스가 미리 모아 넘긴다. 목록 CollectionModel 키 = "courses".
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "courses")
public class CourseResponse {

    private Long id;
    private Long instructorId;
    private String title;
    private CourseKind kind;
    private String organizationCode;
    private String disciplineCode;
    private Set<CertLevel> levels;
    @JsonProperty("isPackage")
    private boolean isPackage;
    private int totalRounds;
    private int price;
    private String description;
    private CourseStatus status;
    private List<Media> media;
    private List<Round> rounds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CourseResponse from(Course c, Map<String, VenueEquipmentResponse> equipmentByRef) {
        return CourseResponse.builder()
                .id(c.getId())
                .instructorId(c.getInstructor() == null ? null : c.getInstructor().getId())
                .title(c.getTitle())
                .kind(c.getKind())
                .organizationCode(c.getOrganizationCode())
                .disciplineCode(c.getDisciplineCode())
                .levels(c.getLevels())
                .isPackage(c.isPackage())
                .totalRounds(c.getTotalRounds())
                .price(c.getPrice())
                .description(c.getDescription())
                .status(c.getStatus())
                .media(c.getMedia().stream().map(Media::from).collect(Collectors.toList()))
                .rounds(c.getRounds().stream().map(r -> Round.from(r, equipmentByRef)).collect(Collectors.toList()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Media {
        private Long id;
        private MediaKind kind;
        private String url;
        private int sortOrder;

        static Media from(CourseMedia m) {
            return Media.builder().id(m.getId()).kind(m.getKind()).url(m.getUrl()).sortOrder(m.getSortOrder()).build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Round {
        private Long id;
        private RoundKind roundKind;
        private Integer roundIndex;
        private boolean platformConfirmed;
        private String description;
        /** EXTRA 전용. */
        private Integer freeCount;
        private Integer perSessionPrice;
        private List<Venue> venues;

        static Round from(CourseRound r, Map<String, VenueEquipmentResponse> equipmentByRef) {
            return Round.builder()
                    .id(r.getId())
                    .roundKind(r.getRoundKind())
                    .roundIndex(r.getRoundIndex())
                    .platformConfirmed(r.isPlatformConfirmed())
                    .description(r.getDescription())
                    .freeCount(r.getFreeCount())
                    .perSessionPrice(r.getPerSessionPrice())
                    .venues(r.getVenues().stream().map(v -> Venue.from(v, equipmentByRef)).collect(Collectors.toList()))
                    .build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Venue {
        private String venueRefId;
        private List<Ticket> tickets;
        /** 위치별 대여 장비 = 강사×위치 가격표에서 합성. 미설정 위치면 null. */
        private VenueEquipmentResponse equipment;

        static Venue from(RoundVenue v, Map<String, VenueEquipmentResponse> equipmentByRef) {
            return Venue.builder()
                    .venueRefId(v.getVenueRefId())
                    .tickets(v.getTickets().stream().map(Ticket::from).collect(Collectors.toList()))
                    .equipment(equipmentByRef.get(v.getVenueRefId()))
                    .build();
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Ticket {
        private String ticketRef;
        private DaypartKind daypart;

        static Ticket from(RoundVenueTicket t) {
            return Ticket.builder().ticketRef(t.getTicketRef()).daypart(t.getDaypart()).build();
        }
    }
}
