package com.diving.pungdong.availability.dto;

import com.diving.pungdong.availability.AvailabilityHold;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SlotStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일정(session) 응답 — 캘린더의 한 점유 블록(위치·정원·사람). 5상태({@link #status})와 점유 수치는 <b>저장값이
 * 아니라 서비스가 파생</b>해 싣는다({@code AvailabilityService}). coverage(예약가능시간)와는 별개 레이어 —
 * 캘린더 응답({@code AvailabilityCalendarResponse})에서 {@code coverage[]} 와 {@code sessions[]} 로 분리된다.
 *
 * <p>{@code applicants} 는 enrollment 도메인이 채우는 풍덩 수강생 요약. {@code venueName} 은 {@code venueRefId}
 * 를 {@code VenueRefResolver} 로 해석한 표시명(미지정/미존재면 null).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AvailabilitySessionResponse {

    private Long id;
    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    /** 유효정원 = 이 일정 override 가 있으면 그것, 없으면 강사 계정 기본값(파생, 저장값 아님). */
    private int capacity;
    /** true = 그 날만 직접 정한 값(override). FE 배지·"기본값 따르기" 노출 판단용. */
    private boolean capacityOverridden;

    /** 파생 표시 상태(AVAILABLE/PENDING/CONFIRMED/EXTERNAL/FULL). */
    private SlotStatus status;

    /** 찬 자리 = confirmedCount + externalCount. */
    private int filled;
    /** 풍덩 확정 점유. */
    private int confirmedCount;
    /** 외부/수동 hold 점유 합. */
    private int externalCount;
    /** 풍덩 대기 신청. */
    private int pendingCount;

    private String venueRefId;
    /** venueRefId 해석 표시명(미지정/미존재면 null). */
    private String venueName;
    private String ticketRef;
    /** ticketRef 해석 이용권 명칭(미지정/미존재면 null). 저장값 아니라 venue 에서 읽어 채움. */
    private String sessionLabel;

    private List<HoldResponse> holds;
    /** 풍덩 수강생 요약(없으면 빈 리스트). */
    private List<ApplicantSummaryResponse> applicants;

    /**
     * 엔티티 → 응답 매핑. 점유 파생값·venueName·applicants 는 호출자가 계산해 넘긴다(N+1 회피 위해 배치 해석).
     */
    public static AvailabilitySessionResponse of(AvailabilitySession s, SlotStatus status,
                                                 int filled, int confirmedCount, int externalCount,
                                                 int pendingCount, String venueName, String ticketName,
                                                 List<ApplicantSummaryResponse> applicants) {
        return AvailabilitySessionResponse.builder()
                .id(s.getId())
                .date(s.getDate())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .capacity(s.effectiveCapacity())
                .capacityOverridden(s.isCapacityOverridden())
                .status(status)
                .filled(filled)
                .confirmedCount(confirmedCount)
                .externalCount(externalCount)
                .pendingCount(pendingCount)
                .venueRefId(s.getVenueRefId())
                .venueName(venueName)
                .ticketRef(s.getTicketRef())
                .sessionLabel(ticketName)
                .holds(s.getHolds().stream().map(HoldResponse::from).collect(Collectors.toList()))
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
