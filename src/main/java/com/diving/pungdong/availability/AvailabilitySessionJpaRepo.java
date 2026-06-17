package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface AvailabilitySessionJpaRepo extends JpaRepository<AvailabilitySession, Long> {

    /** 캘린더 읽기 — 내 일정 중 [from, to] 범위(양끝 포함), 날짜·시작시간 순. */
    List<AvailabilitySession> findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(
            Long instructorId, LocalDate from, LocalDate to);

    /** 같은 (위치,시간) 일정 찾기(join/원자추가용) — venueRefId 는 Java 에서 동치 필터(null 포함). */
    List<AvailabilitySession> findByInstructorIdAndDateAndStartTimeAndEndTime(
            Long instructorId, LocalDate date, LocalTime startTime, LocalTime endTime);

    /** 그 날 coverage 가 session 을 가로지르나 판정용 — 하루 일정 전부. */
    List<AvailabilitySession> findByInstructorIdAndDate(Long instructorId, LocalDate date);
}
