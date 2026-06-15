package com.diving.pungdong.enrollment.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/**
 * 수강신청 요청 — V2 booking ⑤ 최종확인 → 신청. student 는 컨트롤러가 현재 계정으로 주입.
 * 옵션 API({@code GET /enrollments/options})가 준 슬롯의 식별자를 그대로 echo 한다. 서버가 모두 재검증
 * (window 소유 강사=코스 강사 · 블록이 venue 운영블록이며 window 에 ⊆ · 정원 · 장비 소속 · 가격 재계산).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class EnrollmentCreateRequest {

    @NotNull
    private Long courseId;

    @NotNull
    private Long availabilityWindowId;

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
