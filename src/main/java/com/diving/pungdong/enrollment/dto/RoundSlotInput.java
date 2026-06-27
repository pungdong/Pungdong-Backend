package com.diving.pungdong.enrollment.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
}
