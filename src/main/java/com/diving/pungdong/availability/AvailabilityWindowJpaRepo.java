package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AvailabilityWindowJpaRepo extends JpaRepository<AvailabilityWindow, Long> {

    /** 캘린더 읽기 — 내 가용시간 중 [from, to] 범위(양끝 포함), 날짜·시작시간 순. */
    List<AvailabilityWindow> findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(
            Long instructorId, LocalDate from, LocalDate to);
}
