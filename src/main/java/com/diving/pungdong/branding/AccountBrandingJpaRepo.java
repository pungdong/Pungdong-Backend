package com.diving.pungdong.branding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountBrandingJpaRepo extends JpaRepository<AccountBranding, Long> {

    /** 오너 조회 — 없으면 비어 있다(생성은 첫 쓰기가 한다, contract §4.5). */
    Optional<AccountBranding> findByAccountId(Long accountId);

    /**
     * 공개 프로필 — 닉네임으로 연다(D3, handle 폐기). 발행됐고 탈퇴하지 않은 계정만.
     *
     * <p><b>왜 단건이 아니라 List 인가</b>: {@code account.nick_name} 에 아직 UNIQUE 인덱스가 없다
     * (중복 dedupe 는 실데이터 사전 점검 후 별도 마이그레이션). 단건 조회로 두면 중복이 하나라도 있을 때
     * {@code IncorrectResultSizeDataAccessException} 으로 500 이 난다. 그래서 결정적으로 정렬해
     * 가장 오래된 계정을 고른다 — UNIQUE 가 붙은 뒤에도 동작이 달라지지 않는다.
     */
    @Query("select b from AccountBranding b join b.account a "
            + "where a.nickName = :nickName and a.isDeleted = false and b.isPublished = true "
            + "order by a.id asc")
    List<AccountBranding> findPublishedByNickName(@Param("nickName") String nickName);

    /**
     * 추천 카드에 <b>실을 수 있는</b> 강사 계정 id 전부 — 승인된 강사 중 <b>프로필을 발행한</b> 사람만.
     *
     * <p><b>발행 조건이 핵심이다.</b> 카드를 누르면 {@code GET /instructors/{nickName}} 으로 가는데
     * 그 상세는 {@code isPublished = true} 만 연다({@link #findPublishedByNickName}) — 미발행 강사를
     * 추천하면 <b>누르면 400 이 나는 카드</b>가 된다. 갈 곳 없는 추천은 추천이 아니다.
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
}
