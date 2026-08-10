package com.diving.pungdong.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CourseJpaRepo extends JpaRepository<Course, Long>, JpaSpecificationExecutor<Course> {
    List<Course> findAllByInstructorIdOrderByIdDesc(Long instructorId);

    /** 강사의 공개 강의 수 — 브랜딩 CTA 뱃지. 데모 시드를 노출하는 환경용. */
    long countByInstructorIdAndStatus(Long instructorId, CourseStatus status);

    /** 위와 같되 데모 시드 제외 — 런칭 후 둘러보기가 시드를 가리므로 숫자도 같이 가려야 어긋나지 않는다. */
    long countByInstructorIdAndStatusAndSeededFalse(Long instructorId, CourseStatus status);
}
