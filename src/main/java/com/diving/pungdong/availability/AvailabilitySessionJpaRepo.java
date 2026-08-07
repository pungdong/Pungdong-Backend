package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface AvailabilitySessionJpaRepo extends JpaRepository<AvailabilitySession, Long> {

    /**
     * 좌석 점유 판정 <b>직전에 이 세션 행을 비관적 쓰기잠금(SELECT … FOR UPDATE)</b>으로 잡는다 — 동시 신청의
     * "좌석 count → insert" 를 이 행 위에서 직렬화해 오버부킹을 막는다. 잠금은 트랜잭션 커밋까지 유지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AvailabilitySession s where s.id = :id")
    Optional<AvailabilitySession> lockById(@Param("id") Long id);

    /** 캘린더 읽기 — 내 일정 중 [from, to] 범위(양끝 포함), 날짜·시작시간 순. */
    List<AvailabilitySession> findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(
            Long instructorId, LocalDate from, LocalDate to);

    /** 같은 (위치,시간) 일정 찾기(join/원자추가용) — venueRefId 는 Java 에서 동치 필터(null 포함). */
    List<AvailabilitySession> findByInstructorIdAndDateAndStartTimeAndEndTime(
            Long instructorId, LocalDate date, LocalTime startTime, LocalTime endTime);

    /** 그 날 coverage 가 session 을 가로지르나 판정용 — 하루 일정 전부. */
    List<AvailabilitySession> findByInstructorIdAndDate(Long instructorId, LocalDate date);
}
