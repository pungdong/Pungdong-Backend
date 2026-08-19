package com.diving.pungdong.enrollment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * (학생, 강의) 신청 이력 쌍 — 어드민 신고 큐가 "신고자가 그 강의를 실제로 신청한 사람인가" 를
     * 판정하는 데 쓴다(moderation → enrollment 단방향 읽기).
     *
     * <p>한 페이지의 학생·강의 id 를 <b>한 번에</b> 물어 교차곱을 받고 호출부가 정확한 쌍만 고른다 —
     * 행마다 exists 를 날리면 페이지 크기만큼 쿼리가 나간다.
     *
     * <p><b>상태를 보지 않는다.</b> 여기서 알고 싶은 건 "이 강의와 관계가 있는 사람인가" 지 "지금
     * 확정된 수강생인가" 가 아니다 — 취소·거절된 신청이야말로 분쟁 신고의 흔한 배경이다.
     */
    @Query("select e.student.id, e.course.id from Enrollment e "
            + "where e.student.id in :studentIds and e.course.id in :courseIds")
    List<Object[]> findStudentCoursePairs(@Param("studentIds") Collection<Long> studentIds,
                                          @Param("courseIds") Collection<Long> courseIds);
}
