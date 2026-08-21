package com.diving.pungdong.branding;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountBrandingJpaRepo extends JpaRepository<AccountBranding, Long> {

    /** 오너 조회 — 없으면 비어 있다(생성은 첫 쓰기가 한다, contract §4.5). */
    Optional<AccountBranding> findByAccountId(Long accountId);

    // 닉네임으로 공개 프로필을 여는 진입점은 여기가 아니라 PublicProfileResolver 다 —
    // 프로필 행이 없는 계정도 200(빈 프로필)이라, "발행된 행" 이 아니라 "살아있는 계정"에서 출발한다
    // (account.AccountJpaRepo.findActiveByNickName). 옛 findPublishedByNickName 은 그래서 삭제됐다.

    /**
     * 추천 카드에 <b>실을 수 있는</b> 강사 계정 id 전부 — 승인된 강사 중 <b>프로필을 발행한</b> 사람만.
     *
     * <p><b>발행 조건의 근거가 바뀌었다(2026-08-21).</b> 예전엔 "안 걸면 <b>누르면 400 이 나는 카드</b>가
     * 된다" 였는데, 이제 프로필은 모든 계정에 있어서({@code PublicProfileResolver}) 갈 곳 없는 카드라는
     * 문제는 사라졌다. 그래도 조건을 남기는 이유는 <b>추천은 보여줄 게 있는 사람이어야</b> 해서다 —
     * 이 행은 첫 쓰기(프로필 편집·글 작성)로 생기므로 "한 번이라도 뭔가 남긴 강사" 의 근사치가 된다.
     * 단, 유저가 직접 내린 비공개({@code isPublished=false})는 그 자체로 제외 사유다.
     * (기존 디렉토리 {@code GET /instructors/public} 에는 없는 조건이다. 그쪽은 "몇 명이 검수를
     * 통과했나" 를 세는 목록이고, 이쪽은 "지금 보여줄 수 있는 사람" 이다.)
     *
     * <p>카드가 아니라 <b>id 만</b> 돌려주는 이유: 랜덤 N명을 고르려면 후보 전체가 필요한데, 계정·아바타·
     * 종목까지 다 실어 오면 버리는 게 대부분이다. id 로 후보를 받아 셔플한 뒤 <b>고른 N명만</b> 살을 붙인다.
     * 강사가 수만 명이 되면 이 목록 자체가 부담이 되지만(그때는 DB 쪽 샘플링으로 옮긴다), 그 전까지는
     * DB 방언에 의존하는 {@code RAND()} 보다 이쪽이 안전하다.
     */
    @Query("select b.account.id from AccountBranding b "
            + "where b.isPublished = true and b.account.isDeleted = false "
            + "and exists (select 1 from InstructorApplication a where a.account = b.account "
            + "and a.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED)")
    List<Long> findSuggestableInstructorAccountIds();

    /* ═══════════ 강사 둘러보기 (GET /instructors/browse) ═══════════ */

    /**
     * 노출 모수 — <b>목록 쿼리와 카운트 쿼리가 이 한 문자열을 공유한다.</b> 따로 적으면 목록 3건에
     * {@code totalElements} 5 가 되고, 그 어긋남은 화면 끝에 가서야 드러난다(커뮤니티가 먼저 밟은 함정).
     *
     * <p>세 조건이 전부 필요하다: 탈퇴 아님 · <b>브랜딩 발행</b> · 그 종목의 승인 강사.
     * 발행 조건이 핵심 — 상세는 {@code isPublished = true} 만 열기 때문에, 빼면 <b>누르면 400 이 나는
     * 카드</b>가 목록에 섞인다(기존 {@code /instructors/public} 이 그렇다).
     *
     * <p>파라미터가 비어 있을 때의 처리:
     * <ul>
     *   <li>{@code keyword} 는 {@code null} 을 넘기면 조건이 꺼진다(문자열 파라미터라 안전).</li>
     *   <li>{@code organizationCodes} 는 <b>절대 빈 리스트를 넘기지 않는다</b> — JPQL {@code in ()} 은
     *       유효한 SQL 이 아니다. 필터가 꺼졌을 땐 {@code :orgFilterOff = true} 로 단락시키고 리스트에는
     *       더미 1개를 넣는다. (DB 가 {@code or} 를 단락 평가할 의무는 없으므로 {@code in ('')} 이 실제로
     *       실행될 수 있다 — 다만 좌변이 참이라 <b>결과에 영향이 없다</b>. "평가되지 않는다" 가 아니라
     *       "평가돼도 무해하다" 가 정확한 표현이다.)</li>
     * </ul>
     */
    String BROWSE_POPULATION =
            "where b.isPublished = true and b.account.isDeleted = false "
            + "and exists (select 1 from InstructorApplication a where a.account = b.account "
            + "  and a.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED "
            + "  and a.disciplineCode = :disciplineCode) "
            + "and (:keyword is null or lower(b.account.nickName) like :keyword) "
            + "and (:orgFilterOff = true or exists (select 1 from ApplicationCertificate ac "
            + "  where ac.application.account = b.account and ac.application.disciplineCode = :disciplineCode "
            + "  and ac.application.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED "
            + "  and ac.organizationCode in :organizationCodes)) "
            + "and (:hasOpenCourse = false or exists (select 1 from Course oc where oc.instructor = b.account "
            + "  and oc.disciplineCode = :disciplineCode "
            + "  and oc.status = com.diving.pungdong.course.CourseStatus.OPEN and oc.blockedAt is null "
            + "  and (:showSeeded = true or oc.seeded = false))) ";

    /**
     * "공개중인 강의" 의 정의 — <b>강의 둘러보기({@code CourseSpecifications})가 실제로 보여주는 것과
     * 같아야 한다.</b> 아니면 카드엔 "강의 3" 인데 그 강사 강의 목록은 0건인 화면이 나온다. 그래서
     * OPEN · 미차단 · 데모 가림 설정까지 그대로 건다. 종목 한정인 이유는 이 화면이 항상 한 종목
     * 컨텍스트로 진입하기 때문(결정 A2).
     *
     * <p><b>⚠️ {@code CourseSpecifications} 에는 있는데 여기 없는 조건이 하나 있다 — 의도적이다.</b>
     * 둘러보기는 <b>"그 종목 승인 강사의 강의만"</b> 을 추가로 건다({@code course.InstructorApprovalPolicy},
     * 2026-08-22). 여기서 반복하지 않는 이유는 <b>{@link #BROWSE_POPULATION} 이 이미 같은 것을 요구하기
     * 때문</b>이다 — 이 조인이 세는 강의는 전부 {@code c.instructor = b.account} 이고
     * {@code c.disciplineCode = :disciplineCode} 인데, 모수가 바로 그 계정·그 종목의 APPROVED 를 요구한다.
     * 즉 승인 술어는 <b>구성상 항상 참</b>이라 붙여도 결과가 같다.
     *
     * <p>🔴 <b>그래서 이 패리티는 모수에 묶여 있다.</b> 나중에 {@code BROWSE_POPULATION} 에서 승인 조건을
     * 느슨하게 하면(예: 미승인 강사도 목록에 넣기로 하면) 이 카운트가 <b>둘러보기가 감추는 강의까지 세기
     * 시작한다</b> — 그때는 여기에 승인 조건을 명시적으로 추가해야 한다. 모수를 건드리는 사람이 이 문단을
     * 보게 하려고 여기 적어 둔다.
     */
    String OPEN_COURSE_JOIN =
            "left join Course c on c.instructor = b.account and c.disciplineCode = :disciplineCode "
            + "  and c.status = com.diving.pungdong.course.CourseStatus.OPEN and c.blockedAt is null "
            + "  and (:showSeeded = true or c.seeded = false) ";

    /**
     * 카드 한 페이지 = {@code [accountId, openCourseCount]}. 강의 수를 <b>같은 쿼리에서</b> 세므로
     * 필터·정렬·카운트가 한 번에 끝난다(카드마다 세면 페이지 크기만큼 쿼리가 나간다).
     *
     * <p>계정·아바타·종목·단체는 여기서 싣지 않는다 — {@code @ElementCollection}/자식 테이블을 함께
     * 조인하면 행이 곱해지고 {@code group by} 가 지저분해진다. 고른 N명에만 별도 일괄 조회로 살을
     * 붙인다({@code /instructors/public}·추천 카드와 같은 방식).
     *
     * <p>정렬이 <b>메서드로 갈라져 있는 이유</b>: 집계값({@code count})으로 정렬하려면 {@code order by}
     * 에 그 식이 들어가야 하는데 {@code Pageable} 의 {@code Sort} 로는 표현할 수 없다. 클라이언트
     * 정렬을 태우지 않는다는 규약과도 맞다 — 정렬 창구는 화이트리스트 enum 뿐이다.
     *
     * <p>두 정렬 모두 <b>tie-break 로 {@code b.account.id desc}</b> 를 붙인다. offset 페이지네이션이라
     * 순서가 결정적이지 않으면 같은 강사가 1페이지와 2페이지에 모두 나온다.
     */
    @Query(value = "select b.account.id, count(c.id) from AccountBranding b " + OPEN_COURSE_JOIN
            + BROWSE_POPULATION
            + "group by b.account.id order by b.account.id desc",
            countQuery = "select count(distinct b.account.id) from AccountBranding b " + BROWSE_POPULATION)
    Page<Object[]> browseInstructorsLatest(@Param("disciplineCode") String disciplineCode,
                                           @Param("keyword") String keyword,
                                           @Param("orgFilterOff") boolean orgFilterOff,
                                           @Param("organizationCodes") Collection<String> organizationCodes,
                                           @Param("hasOpenCourse") boolean hasOpenCourse,
                                           @Param("showSeeded") boolean showSeeded,
                                           Pageable pageable);

    /** {@link #browseInstructorsLatest} 와 같은 모수·같은 카운트, 정렬만 강의 많은순. */
    @Query(value = "select b.account.id, count(c.id) from AccountBranding b " + OPEN_COURSE_JOIN
            + BROWSE_POPULATION
            + "group by b.account.id order by count(c.id) desc, b.account.id desc",
            countQuery = "select count(distinct b.account.id) from AccountBranding b " + BROWSE_POPULATION)
    Page<Object[]> browseInstructorsByCourseCount(@Param("disciplineCode") String disciplineCode,
                                                  @Param("keyword") String keyword,
                                                  @Param("orgFilterOff") boolean orgFilterOff,
                                                  @Param("organizationCodes") Collection<String> organizationCodes,
                                                  @Param("hasOpenCourse") boolean hasOpenCourse,
                                                  @Param("showSeeded") boolean showSeeded,
                                                  Pageable pageable);

    /** 고른 강사들의 브랜딩 표면 필드(한 줄 소개·활동지역) 일괄 — 카드마다 조회하지 않는다. */
    @Query("select b from AccountBranding b where b.account.id in :accountIds")
    List<AccountBranding> findAllByAccountIdIn(@Param("accountIds") Collection<Long> accountIds);
}
