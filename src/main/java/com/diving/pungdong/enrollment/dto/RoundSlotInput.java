package com.diving.pungdong.enrollment.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 회차 슬롯 선택 입력 — 1회차 신청({@link EnrollmentCreateRequest})과 2회차+ 신청({@link RoundScheduleRequest})이
 * 공유하는 슬롯 필드. 서버가 이 값으로 venue·블록·coverage·만석·장비·가격을 재검증한다.
 */
public interface RoundSlotInput {
    LocalDate getDate();
    String getVenueRefId();
    String getTicketRef();
    LocalTime getBlockStart();
    LocalTime getBlockEnd();
    List<String> getEquipmentRefs();

    /** 선택 장비의 사이즈(itemRef → "270"·"L"). 사이즈 있는 품목만. 없거나 미선택이면 null 스냅샷. */
    Map<String, String> getEquipmentSizes();
}
