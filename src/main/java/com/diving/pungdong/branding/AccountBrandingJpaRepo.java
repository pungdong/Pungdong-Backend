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
}
