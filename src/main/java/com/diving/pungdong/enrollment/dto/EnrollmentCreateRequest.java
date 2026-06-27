package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 수강신청 요청 — V2 booking ⑤ 최종확인 → 신청. student 는 컨트롤러가 현재 계정으로 주입.
 * 옵션 API({@code GET /enrollments/options})가 준 슬롯의 식별자를 그대로 echo 한다. 서버가 모두 재검증
 * (코스 1회차 위치/이용권 · 블록이 venue 운영블록이며 강사 coverage 에 통째로 ⊆ · 정원 · 장비 소속 · 가격 재계산).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class EnrollmentCreateRequest implements RoundSlotInput {

    @NotNull
    private Long courseId;

    /** 신청 날짜(옵션 슬롯 date echo). 예전 availabilityWindowId 대체 — 이제 (date,위치,블록)이 슬롯 식별자. */
    @NotNull
    private LocalDate date;

    @NotNull
    private String venueRefId;

    @NotNull
    private String ticketRef;

    @NotNull
    private LocalTime blockStart;

    @NotNull
    private LocalTime blockEnd;

    /** 선택한 대여 장비 식별자(VenueEquipmentResponse.Item.id 문자열). 비면 장비 없음. */
    private List<String> equipmentRefs;
}
