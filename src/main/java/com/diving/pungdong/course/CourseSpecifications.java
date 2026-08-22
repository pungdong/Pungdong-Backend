package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.dto.CourseBrowseCondition;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;

/**
 * 공개 둘러보기 동적 쿼리 — repo 규약대로 {@code JpaSpecificationExecutor} + 이 sibling 유틸.
 * (원래 v1 {@code LectureSpecifications} 에서 온 패턴 — 그쪽은 레거시 청산으로 삭제됐다.)
 * ES 안 씀(Phase 3 제거 완료). 항상 OPEN 만 노출.
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
                .and(notBlocked())
                .and(instructorApproved())
                .and(disciplineEq(c.getDisciplineCode()))
                .and(instructorNickNameEq(c.getInstructorNickName()))
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
     * 어드민이 신고를 조치해 차단한 강의 제외.
     *
     * <p><b>{@link #excludeSeeded()} 와 달리 조건 없이 항상 붙는다</b> — 데모 노출은 사이트 설정으로
     * 켜고 끄는 축이지만 조치는 그런 게 아니다. 그래서 호출부가 고를 수 있는 자리가 아니라
     * {@link #matching} 안에 박혀 있다({@code statusOpen} 과 같은 취급).
     */
    private static Specification<Course> notBlocked() {
        return (root, query, cb) -> cb.isNull(root.get("blockedAt"));
    }

    /**
     * <b>정식 강사(그 종목 승인)의 강의만</b> — {@link #notBlocked()} 와 같이 조건 없이 항상 붙는다.
     *
     * <p>강사 검수가 수동이라 신청자는 승인 전에도 강의를 만들어 둘 수 있다(의도된 정책). 그 강의가
     * 둘러보기에 뜨면 <b>아직 정식 강사가 아닌 사람의 상품이 팔린다.</b> 정책·다른 경로는
     * {@link InstructorApprovalPolicy}.
     *
     * <p>메모리 필터가 아니라 <b>쿼리 안의 exists</b> 인 이유는 차단 필터와 같다 — 페이지를 받아 걸러내면
     * 페이지가 짧아지고 {@code totalElements} 가 거짓이 된다.
     */
    private static Specification<Course> instructorApproved() {
        return (root, query, cb) -> {
            Subquery<Long> approved = query.subquery(Long.class);
            Root<InstructorApplication> application = approved.from(InstructorApplication.class);
            approved.select(application.get("id"));
            approved.where(
                    cb.equal(application.get("account"), root.get("instructor")),
                    cb.equal(application.get("disciplineCode"), root.get("disciplineCode")),
                    cb.equal(application.get("status"), InstructorApplicationStatus.APPROVED));
            return cb.exists(approved);
        };
    }

    /**
     * 데모(seeded) 코스 제외 — {@code siteSettings.showSeededCourses=false}(런칭 후) 일 때 둘러보기에서
     * 샘플을 가린다. 데이터는 지우지 않고 노출만 끈다(데이터 ↔ 노출 분리). 노출 ON 이면 호출부가 안 붙인다.
     */
    public static Specification<Course> excludeSeeded() {
        return (root, query, cb) -> cb.isFalse(root.get("seeded"));
    }

    /**
     * 특정 계정이 저장(북마크)한 강의만 — "저장한 강의" 목록({@code ?bookmarkedByMe=true}).
     *
     * <p><b>JOIN 이 아니라 exists 서브쿼리</b>인 이유는 두 가지다. (1) 지역·레벨 필터가
     * {@code query.distinct(true)} 를 켜는데, DISTINCT 와 조인 컬럼을 섞으면 MySQL 이
     * {@code ORDER BY} 단계에서 거부한다(3065) — H2 는 통과해 <b>테스트만 초록인 상태</b>가 된다.
     * (2) 조인은 행을 늘릴 수 있어 {@code totalElements} 를 거짓으로 만들 여지가 있다. exists 는 둘 다
     * 없다. {@code instructorApproved()} 가 같은 이유로 exists 인 것과 짝이다.
     *
     * <p>그래서 <b>정렬은 저장 시각이 아니라 기존 화이트리스트</b>({@code LATEST}·가격)를 쓴다 —
     * "저장한 순" 은 조인이 필요해 위 (1) 에 걸린다. 커뮤니티의 저장한 글 목록도 같다(글 최신순).
     * 필요해지면 별도 쿼리 경로로 따로 붙일 일이다(인기순 피드가 그렇게 붙어 있다).
     */
    public static Specification<Course> bookmarkedBy(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> saved = query.subquery(Long.class);
            Root<CourseBookmark> bookmark = saved.from(CourseBookmark.class);
            saved.select(bookmark.get("course").get("id"));
            saved.where(
                    cb.equal(bookmark.get("course").get("id"), root.get("id")),
                    cb.equal(bookmark.get("account").get("id"), accountId));
            return cb.exists(saved);
        };
    }

    /**
     * 강사 축 — <b>닉네임 정확 일치</b>. "이 강사의 강의만" (강사 카드의 "강의 보기" → 둘러보기).
     *
     * <p><b>{@link #keywordLike} 의 부분일치와 의도적으로 다르다.</b> 검색어는 사람이 친 말이라
     * 넓게 잡아야 하지만, 이 축은 <b>클라이언트가 이미 특정한 한 강사</b>를 가리킨다. 부분일치면
     * {@code "김민지"} 가 {@code "김민지2"}·{@code "김민지스쿨"} 까지 끌어와 <b>남의 강의가 그 강사의
     * 목록에 섞인다</b>. 그래서 여기선 {@code =} 다.
     *
     * <p>없는 닉네임은 <b>400 이 아니라 빈 페이지</b>다 — {@code disciplineCode} 와 같은 규칙이고,
     * 레포 규약(예상된 음성 결과는 200 + 결과 필드)이다. 그리고 닉네임 존재 여부를 상태코드로 흘리면
     * 그 자체가 enumeration oracle 이 된다.
     *
     * <p>JOIN 이 아니라 <b>exists 서브쿼리</b>인 이유는 {@link #bookmarkedBy}/{@link #instructorApproved}
     * 와 같다 — 지역·레벨 필터가 켜는 {@code distinct} 와 조인 컬럼이 섞이면 MySQL 이 거부하고(3065)
     * H2 는 통과해 <b>테스트만 초록</b>이 된다. {@code instructor} 는 {@code @ManyToOne} 이라 바깥 쿼리는
     * FK 컬럼만 읽는다(조인 0개).
     */
    private static Specification<Course> instructorNickNameEq(String nickName) {
        if (!StringUtils.hasText(nickName)) {
            return null;
        }
        String exact = nickName.trim();
        return (root, query, cb) -> {
            Subquery<Long> owner = query.subquery(Long.class);
            Root<Account> instructor = owner.from(Account.class);
            owner.select(instructor.get("id"));
            owner.where(
                    cb.equal(instructor.get("id"), root.get("instructor").get("id")),
                    cb.equal(instructor.get("nickName"), exact));
            return cb.exists(owner);
        };
    }

    private static Specification<Course> disciplineEq(String disciplineCode) {
        return !StringUtils.hasText(disciplineCode) ? null
                : (root, query, cb) -> cb.equal(root.get("disciplineCode"), disciplineCode);
    }

    /**
     * 검색어 — <b>제목 OR 강사 닉네임</b> 부분일치(대소문자 무시).
     *
     * <p>강사명까지 잡는 이유: 사용자는 "김민지 선생님 수업"을 찾을 때 강사 이름을 친다. 제목만 보던
     * 시절엔 그 검색이 0건이었고, 루트 {@code CLAUDE.md} 는 이미 "제목/강사 LIKE" 라고 <b>적혀 있었다</b>
     * (문서가 앞서 있었던 셈).
     *
     * <p>강사 조인은 <b>LEFT</b> 다 — INNER 면 강사가 없는(계정이 지워진) 코스가 제목이 맞는데도
     * 검색에서 사라진다. {@code @ManyToOne} 이라 행이 늘지 않으므로 {@code distinct} 는 붙이지 않는다.
     */
    private static Specification<Course> keywordLike(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String like = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Join<Object, Object> instructor = root.join("instructor", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(instructor.get("nickName")), like));
        };
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
