package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 회차(EnrollmentRound) 레포 — 슬롯·상태가 회차로 내려오면서 옛 {@code EnrollmentJpaRepo} 의 session/status 집계
 * 쿼리가 여기로 이동했다. 정원 집계·캘린더 점유·강사 목록은 모두 회차 단위로 센다.
 */
public interface EnrollmentRoundJpaRepo extends JpaRepository<EnrollmentRound, Long> {

    /** 한 일정의 상태 집합에 드는 회차 수 — 점유(결제대기+확정, {@link EnrollmentStatus#OCCUPYING}) 합산용. */
    int countByAvailabilitySessionIdAndStatusIn(Long sessionId, Collection<EnrollmentStatus> statuses);

    /** 한 일정의 상태 집합에 드는 회차들 — 활성 조회·삭제 판정. */
    List<EnrollmentRound> findByAvailabilitySessionIdAndStatusIn(Long sessionId, Collection<EnrollmentStatus> statuses);

    /** 한 일정의 모든 회차(상태 무관) — 빈 일정 삭제 시 FK 끊기용. */
    List<EnrollmentRound> findByAvailabilitySessionId(Long sessionId);

    /** 여러 일정의 활성 회차 일괄 조회 — 캘린더 N+1 회피. */
    List<EnrollmentRound> findByAvailabilitySessionIdInAndStatusIn(Collection<Long> sessionIds,
                                                                   Collection<EnrollmentStatus> statuses);

    /** 강사 — 내 코스로 들어온 회차(상태별). enrollment.course.instructor.id 경유. */
    List<EnrollmentRound> findByEnrollment_Course_Instructor_IdAndStatusOrderByIdDesc(Long instructorId,
                                                                                      EnrollmentStatus status);

    /** 한 학생의 회차(최신순) — 주로 테스트/내부 조회. enrollment.student.id 경유. */
    List<EnrollmentRound> findByEnrollment_Student_IdOrderByIdDesc(Long studentId);
}
