package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EnrollmentJpaRepo extends JpaRepository<Enrollment, Long> {

    /** 내 신청 목록 — 최신순. */
    List<Enrollment> findByStudentIdOrderByIdDesc(Long studentId);

    /** 강사 — 내 코스로 들어온 신청(상태별). enrollment.course.instructor.id 경유. */
    List<Enrollment> findByCourse_Instructor_IdAndStatusOrderByIdDesc(Long instructorId, EnrollmentStatus status);

    /** 한 일정(session)의 특정 상태 신청 수 — 정원 집계. */
    int countByAvailabilitySessionIdAndStatus(Long sessionId, EnrollmentStatus status);

    /** 한 일정의 상태 집합에 드는 신청들 — 활성(PENDING/CONFIRMED) 조회·join/삭제 판정. */
    List<Enrollment> findByAvailabilitySessionIdAndStatusIn(Long sessionId, Collection<EnrollmentStatus> statuses);

    /** 한 일정의 모든 신청(상태 무관) — 빈 일정 삭제 시 FK 끊기용. */
    List<Enrollment> findByAvailabilitySessionId(Long sessionId);

    /** 여러 일정의 활성 신청 일괄 조회 — 캘린더 N+1 회피. */
    List<Enrollment> findByAvailabilitySessionIdInAndStatusIn(Collection<Long> sessionIds,
                                                              Collection<EnrollmentStatus> statuses);
}
