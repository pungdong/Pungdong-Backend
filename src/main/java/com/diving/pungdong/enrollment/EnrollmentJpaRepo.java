package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EnrollmentJpaRepo extends JpaRepository<Enrollment, Long> {

    /** 내 신청 목록 — 최신순. */
    List<Enrollment> findByStudentIdOrderByIdDesc(Long studentId);

    /** 강사 — 내 코스로 들어온 신청(상태별). enrollment.course.instructor.id 경유. */
    List<Enrollment> findByCourse_Instructor_IdAndStatusOrderByIdDesc(Long instructorId, EnrollmentStatus status);

    /** 한 window 의 특정 상태 신청 수 — 정원/캘린더 집계. */
    int countByAvailabilityWindowIdAndStatus(Long windowId, EnrollmentStatus status);

    /** 한 window 의 상태 집합에 드는 신청들 — 활성(PENDING/CONFIRMED) 조회·bind 판정. */
    List<Enrollment> findByAvailabilityWindowIdAndStatusIn(Long windowId, Collection<EnrollmentStatus> statuses);

    /** 여러 window 의 활성 신청 일괄 조회 — 캘린더 목록 N+1 회피. */
    List<Enrollment> findByAvailabilityWindowIdInAndStatusIn(Collection<Long> windowIds,
                                                             Collection<EnrollmentStatus> statuses);
}
