package com.diving.pungdong.branding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
