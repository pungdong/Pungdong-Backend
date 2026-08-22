package com.diving.pungdong.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 강의 북마크 조회. 카드 목록용 집계·뷰어상태는 <b>페이지 단위 일괄 조회</b>다(카드마다 세면 N+1) —
 * 커뮤니티 {@code CommunityPostBookmarkJpaRepo} 와 같은 형태.
 */
public interface CourseBookmarkJpaRepo extends JpaRepository<CourseBookmark, Long> {

    Optional<CourseBookmark> findByCourseIdAndAccountId(Long courseId, Long accountId);

    long countByCourseId(Long courseId);

    @Query("select b.course.id, count(b) from CourseBookmark b "
            + "where b.course.id in :courseIds group by b.course.id")
    List<Object[]> countByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    @Query("select b.course.id from CourseBookmark b "
            + "where b.account.id = :accountId and b.course.id in :courseIds")
    List<Long> findBookmarkedCourseIds(@Param("accountId") Long accountId,
                                       @Param("courseIds") Collection<Long> courseIds);
}
