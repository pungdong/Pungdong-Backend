package com.diving.pungdong.concurrency;

import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseSpecifications;
import com.diving.pungdong.course.dto.CourseBrowseCondition;
import com.diving.pungdong.venue.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 둘러보기 필터 SQL 이 <b>실 MySQL 에서 실행되는지</b>만 본다(결과 검증은 H2 의
 * {@code CourseBrowseUseCaseTest} 가 한다 — 여기 관심사는 방언이다).
 *
 * <p>왜 따로 도는가: 지역·레벨 필터는 {@code query.distinct(true)} 를 켜는데, MySQL 은
 * <b>DISTINCT + ORDER BY</b> 조합에서 SELECT 목록에 없는 컬럼을 참조하면 거부한다(3065). H2 는 그걸
 * 그냥 통과시켜 <b>테스트만 초록</b>인 상태를 만든다 — 저장(북마크) 필터를 조인으로 짰다면 여기서
 * 터졌을 자리다. 그래서 {@code instructorNickName}(강사 축)도 조인이 아니라 exists 로 짰고, 이
 * 테스트가 그 선택을 실 엔진에서 확인한다. 새 필터 축이 붙으면 이 조합에 한 줄 추가할 것.
 */
class CourseBrowseFilterMySqlTest extends MySqlConcurrencyTestBase {

    @Autowired CourseJpaRepo courseRepo;

    @Test
    @DisplayName("강사 축 + 지역(distinct) + 정렬을 한꺼번에 걸어도 실 MySQL 이 쿼리를 받는다 (DISTINCT/ORDER BY 3065 방어)")
    void browseFilterCombinationRunsOnMySql() {
        CourseBrowseCondition condition = CourseBrowseCondition.builder()
                .disciplineCode("FREEDIVING")
                .instructorNickName("김민지")   // exists 서브쿼리(조인 아님)
                .keyword("딥")                  // 강사 LEFT JOIN
                .region(Region.SEOUL_GYEONGGI) // distinct(true) 를 켠다
                .build();
        Specification<Course> spec = CourseSpecifications.matching(condition)
                .and(CourseSpecifications.excludeSeeded());

        assertThatCode(() -> courseRepo.findAll(spec,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))))
                .doesNotThrowAnyException();
    }
}
