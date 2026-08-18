package com.diving.pungdong.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CourseJpaRepo extends JpaRepository<Course, Long>, JpaSpecificationExecutor<Course> {
    List<Course> findAllByInstructorIdOrderByIdDesc(Long instructorId);

    /** 강사의 공개 강의 수 — 브랜딩 CTA 뱃지. 데모 시드를 노출하는 환경용. */
    @Query("select count(c) from Course c "
            + "where c.instructor.id = :instructorId and c.status = :status and c.blockedAt is null")
    long countByInstructorIdAndStatus(@Param("instructorId") Long instructorId,
                                      @Param("status") CourseStatus status);

    /** 위와 같되 데모 시드 제외 — 런칭 후 둘러보기가 시드를 가리므로 숫자도 같이 가려야 어긋나지 않는다. */
    @Query("select count(c) from Course c where c.instructor.id = :instructorId "
            + "and c.status = :status and c.seeded = false and c.blockedAt is null")
    long countByInstructorIdAndStatusAndSeededFalse(@Param("instructorId") Long instructorId,
                                                    @Param("status") CourseStatus status);

    /**
     * 여러 강사의 공개 강의 수를 한 번에 — 커뮤니티 피드의 "강사 · 강의 N" 칩용.
     *
     * <p>피드 한 페이지에 작성자가 여러 명이라 강사마다 {@code countByInstructorIdAndStatus} 를 부르면
     * 페이지 크기만큼 쿼리가 나간다(N+1). 작성자 id 를 모아 group by 한 번으로 끝낸다 —
     * 브랜딩 그리드가 미디어를 일괄 조회해 메모리에서 그룹핑하는 것과 같은 패턴.
     *
     * <p>반환은 {@code [instructorId, count]} 행들이다. 강의가 0개인 강사는 행 자체가 없으므로
     * 호출부가 기본값 0 으로 채운다.
     */
    @Query("select c.instructor.id, count(c) from Course c "
            + "where c.instructor.id in :instructorIds and c.status = :status and c.blockedAt is null "
            + "group by c.instructor.id")
    List<Object[]> countByInstructorIdsAndStatus(@Param("instructorIds") Collection<Long> instructorIds,
                                                 @Param("status") CourseStatus status);

    /** 위와 같되 데모 시드 제외. 단건 버전과 같은 이유로 짝을 맞춘다. */
    @Query("select c.instructor.id, count(c) from Course c "
            + "where c.instructor.id in :instructorIds and c.status = :status and c.seeded = false "
            + "and c.blockedAt is null group by c.instructor.id")
    List<Object[]> countByInstructorIdsAndStatusExcludingSeeded(@Param("instructorIds") Collection<Long> instructorIds,
                                                                @Param("status") CourseStatus status);
}
