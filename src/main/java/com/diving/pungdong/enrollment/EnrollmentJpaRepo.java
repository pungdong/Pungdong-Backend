package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 수강(Enrollment) 부모 레포. 슬롯·상태 단위 집계는 회차로 내려가 {@link EnrollmentRoundJpaRepo} 가 가진다 —
 * 여기는 학생별 수강 컨테이너 조회만 남는다.
 */
public interface EnrollmentJpaRepo extends JpaRepository<Enrollment, Long> {

    /** 내 수강 목록 — 최신순. 회차는 {@code enrollment.getRounds()}. */
    List<Enrollment> findByStudentIdOrderByIdDesc(Long studentId);
}
