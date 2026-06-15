package com.diving.pungdong.availability.dto;

import com.diving.pungdong.availability.AvailabilityHold;
import com.diving.pungdong.availability.AvailabilityWindow;
import com.diving.pungdong.availability.SlotStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 가용시간 window 응답 — 캘린더 한 블록. CollectionModel 키 = "windows". 5상태({@link #status})와 정원
 * 점유 수치는 <b>저장값이 아니라 서비스가 파생</b>해 싣는다({@code AvailabilityService}).
 *
 * <p>{@code applicants} 는 v1 에서 항상 빈 배열(enrollment 미연동, 모양만 forward-compatible).
 * {@code venueName} 은 {@code venueRefId} 를 {@code VenueRefResolver} 로 해석한 표시명(미존재면 null).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "windows")
public class AvailabilityWindowResponse {

    private Long id;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;
    private int capacity;

    /** 파생 표시 상태(AVAILABLE/PENDING/CONFIRMED/EXTERNAL/FULL). */
    private SlotStatus status;

    /** 총 점유 = confirmedCount + externalCount + pendingCount 와 무관한 '찬 자리'(confirmed+external). */
    private int filled;
    /** 풍덩 확정 점유 — v1 미연동(항상 0). */
    private int confirmedCount;
    /** 외부/수동 hold 점유 합. */
    private int externalCount;
    /** 풍덩 대기 신청 — v1 미연동(항상 0). */
    private int pendingCount;

    private String venueRefId;
    /** venueRefId 해석 표시명(미지정/미존재면 null). */
    private String venueName;
    private String sessionLabel;

    private List<HoldResponse> holds;
    /** v1 빈 배열 — enrollment 가 붙으면 채워진다. */
    private List<ApplicantSummaryResponse> applicants;

    /**
     * 엔티티 → 응답 매핑. 점유 파생값·venueName·applicants 는 호출자가 계산해 넘긴다(venueName/applicants 는
     * N+1 회피 위해 배치 해석). applicants 는 enrollment 도메인이 채우는 풍덩 수강생 요약(없으면 빈 리스트).
     */
    public static AvailabilityWindowResponse of(AvailabilityWindow w, SlotStatus status,
                                                int filled, int confirmedCount, int externalCount,
                                                int pendingCount, String venueName,
                                                List<ApplicantSummaryResponse> applicants) {
        return AvailabilityWindowResponse.builder()
                .id(w.getId())
                .date(w.getDate())
                .startTime(w.getStartTime())
                .endTime(w.getEndTime())
                .capacity(w.getCapacity())
                .status(status)
                .filled(filled)
                .confirmedCount(confirmedCount)
                .externalCount(externalCount)
                .pendingCount(pendingCount)
                .venueRefId(w.getVenueRefId())
                .venueName(venueName)
                .sessionLabel(w.getSessionLabel())
                .holds(w.getHolds().stream().map(HoldResponse::from).collect(Collectors.toList()))
                .applicants(applicants == null ? new ArrayList<>() : applicants)
                .build();
    }

    @Getter @Setter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class HoldResponse {
        private Long id;
        private int count;
        /** null 이면 ± 빠른조정, 값 있으면 외부예약. */
        private String memo;

        static HoldResponse from(AvailabilityHold h) {
            return HoldResponse.builder()
                    .id(h.getId())
                    .count(h.getCount())
                    .memo(h.getMemo())
                    .build();
        }
    }
}
