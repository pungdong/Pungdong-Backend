package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 수강(Enrollment) 부모 레포. 슬롯·상태 단위 집계는 회차로 내려가 {@link EnrollmentRoundJpaRepo} 가 가진다 —
 * 여기는 학생별 수강 컨테이너 조회만 남는다.
 */
public interface EnrollmentJpaRepo extends JpaRepository<Enrollment, Long> {

    /** 내 수강 목록 — 최신순. 회차는 {@code enrollment.getRounds()}. */
    List<Enrollment> findByStudentIdOrderByIdDesc(Long studentId);

    /** 강사가 받은 수강(이 강사 코스의 모든 수강) — 강사 수강관리 hub. 회차는 {@code enrollment.getRounds()}. */
    List<Enrollment> findByCourse_Instructor_IdOrderByIdDesc(Long instructorId);

    /**
     * 강사의 누적 수강생 수 — 브랜딩 페이지 헤더 통계. 같은 학생이 여러 코스를 들어도 <b>1명</b>이다.
     *
     * <p>상태가 {@link Enrollment} 가 아니라 <b>회차</b>에 있어 회차에서 시작해 조인한다. 확정
     * (CONFIRMED)된 회차가 하나라도 있으면 그 학생을 센다 — 신청만 하고 취소·거절된 건은 빠진다.
     */
    @Query("select count(distinct e.student.id) from EnrollmentRound r join r.enrollment e "
            + "where e.course.instructor.id = :instructorId and r.status = :status")
    long countDistinctStudentsOfInstructor(@Param("instructorId") Long instructorId,
                                           @Param("status") EnrollmentStatus status);
}
