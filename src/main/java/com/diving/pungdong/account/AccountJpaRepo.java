package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountJpaRepo extends JpaRepository<Account, Long> {
    Boolean existsByEmail(String email);

    Boolean existsByNickName(String nickName);

    Optional<Account> findByEmail(String email);

    Optional<Account> findByNickName(String nickName);

    /**
     * 공개 프로필({@code GET /instructors/{nickName}}) 진입점 — 살아있는 계정만, <b>가장 오래된 것 먼저</b>.
     *
     * <p><b>왜 단건({@code Optional})이 아닌가</b>: {@code account.nick_name} 에 아직 UNIQUE 인덱스가
     * 없다(dedupe 가 유저 식별자를 바꾸는 동작이라 실데이터 점검 후 별도 마이그레이션). 단건 조회로 두면
     * 중복이 하나라도 있는 순간 500 이 난다. 결정적 정렬 + 첫 건이라야 같은 URL 이 항상 같은 사람을 연다.
     */
    @Query("select a from Account a where a.nickName = :nickName and a.isDeleted = false order by a.id asc")
    List<Account> findActiveByNickName(@Param("nickName") String nickName);

    /**
     * 익명화 대상 = 탈퇴했고(soft delete) 유예기간이 지났으며 아직 익명화 안 된 계정.
     * threshold = now - graceDays. id 만 뽑아 각 건을 독립 트랜잭션으로 익명화한다.
     */
    @Query("select a.id from Account a " +
            "where a.isDeleted = true and a.anonymizedAt is null " +
            "and a.deletedAt is not null and a.deletedAt < :threshold")
    List<Long> findIdsToAnonymize(@Param("threshold") OffsetDateTime threshold);
}
