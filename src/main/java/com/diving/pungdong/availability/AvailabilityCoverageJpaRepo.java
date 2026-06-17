package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AvailabilityCoverageJpaRepo extends JpaRepository<AvailabilityCoverage, Long> {

    /** 한 강사의 하루 coverage 구간들 — 정규화(머지) 시 통째 로드해 교체. */
    List<AvailabilityCoverage> findByInstructorIdAndDate(Long instructorId, LocalDate date);

    /** 캘린더 읽기 — [from, to] 범위(양끝 포함), 날짜·시작 순. */
    List<AvailabilityCoverage> findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(
            Long instructorId, LocalDate from, LocalDate to);
}
