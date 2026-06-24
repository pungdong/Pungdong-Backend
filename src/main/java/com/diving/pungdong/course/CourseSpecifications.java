package com.diving.pungdong.course;

import com.diving.pungdong.course.dto.CourseBrowseCondition;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

/**
 * 공개 둘러보기 동적 쿼리 — repo 규약대로 {@code JpaSpecificationExecutor} + 이 sibling 유틸(legacy
 * {@code LectureSpecifications} 패턴). ES 안 씀(Phase 3 제거 대상). 항상 OPEN 만 노출.
 *
 * <p><b>필터의 종류·레벨은 평탄화 멀티칩(OR)</b> — 시안 필터 시트는 [체험·L1·L2·L3·트레이닝]을 한 줄로
 * 펼쳐 멀티선택시키고({@code home-data.jsx} 의 {@code FILTER_LEVELS}), 결과는 그 칩들의 합집합이다.
 * 코스 <i>작성</i> 화면의 계단식(종류 라디오 → 자격이면 레벨)과는 <b>의도적으로 다르다</b> — 둘러보기는
 * 탐색 편의가 우선이라 평탄하게 둔다. 그래서 {@code kinds}(체험·트레이닝)와 {@code levels}(L1~L3)를 OR 로
 * 묶는다. (필터엔 'CERTIFICATION' 칩 자체가 없어 {@code kinds} 엔 TRIAL/TRAINING 만 온다 — 자격 과정은
 * 레벨 칩으로 직접 표현.)
 *
 * <p>지역/레벨은 {@code @ElementCollection} 이라 JOIN 이 필요해 {@code query.distinct(true)} 로 행 중복을
 * 막는다. 레벨 분기는 종류 분기와 OR 라 LEFT JOIN 으로 — 레벨이 없는 코스(체험/트레이닝)가 inner join 에
 * 걸러져 OR 의 {@code kinds} 분기에서 사라지지 않게.
 */
public final class CourseSpecifications {

    private CourseSpecifications() {
    }

    public static Specification<Course> matching(CourseBrowseCondition c) {
        return Specification.where(statusOpen())
                .and(disciplineEq(c.getDisciplineCode()))
                .and(keywordLike(c.getKeyword()))
                .and(regionContains(c.getRegion()))
                .and(kindOrLevel(c.getKinds(), c.getLevels()))
                .and(organizationIn(c.getOrganizationCodes()))
                .and(priceGoe(c.getMinPrice()))
                .and(priceLoe(c.getMaxPrice()));
    }

    private static Specification<Course> statusOpen() {
        return (root, query, cb) -> cb.equal(root.get("status"), CourseStatus.OPEN);
    }

    /**
     * 데모(seeded) 코스 제외 — {@code siteSettings.showSeededCourses=false}(런칭 후) 일 때 둘러보기에서
     * 샘플을 가린다. 데이터는 지우지 않고 노출만 끈다(데이터 ↔ 노출 분리). 노출 ON 이면 호출부가 안 붙인다.
     */
    public static Specification<Course> excludeSeeded() {
        return (root, query, cb) -> cb.isFalse(root.get("seeded"));
    }

    private static Specification<Course> disciplineEq(String disciplineCode) {
        return !StringUtils.hasText(disciplineCode) ? null
                : (root, query, cb) -> cb.equal(root.get("disciplineCode"), disciplineCode);
    }

    private static Specification<Course> keywordLike(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String like = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), like);
    }

    private static Specification<Course> regionContains(com.diving.pungdong.venue.Region region) {
        return region == null ? null
                : (root, query, cb) -> {
            query.distinct(true);
            Join<Object, Object> rj = root.join("regions", JoinType.INNER);
            return cb.equal(rj, region);
        };
    }

    /**
     * 평탄 멀티칩 — 체험/트레이닝({@code kinds})과 자격 레벨({@code levels})을 OR 합집합으로. 레벨 분기는
     * 자격 과정만 매칭(CERTIFICATION & level ∈ levels), LEFT JOIN 이라 레벨 없는 코스가 kinds 분기에서
     * 사라지지 않는다.
     */
    private static Specification<Course> kindOrLevel(List<CourseKind> kinds, List<CertLevel> levels) {
        boolean hasKinds = !CollectionUtils.isEmpty(kinds);
        boolean hasLevels = !CollectionUtils.isEmpty(levels);
        if (!hasKinds && !hasLevels) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>();
            if (hasKinds) {
                ors.add(root.get("kind").in(kinds));
            }
            if (hasLevels) {
                query.distinct(true);
                Join<Object, Object> lj = root.join("levels", JoinType.LEFT);
                ors.add(cb.and(
                        cb.equal(root.get("kind"), CourseKind.CERTIFICATION),
                        lj.in(levels)));
            }
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    private static Specification<Course> organizationIn(List<String> organizationCodes) {
        return CollectionUtils.isEmpty(organizationCodes) ? null
                : (root, query, cb) -> root.get("organizationCode").in(organizationCodes);
    }

    private static Specification<Course> priceGoe(Integer minPrice) {
        return minPrice == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice);
    }

    private static Specification<Course> priceLoe(Integer maxPrice) {
        return maxPrice == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice);
    }
}
