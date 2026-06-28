package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountJpaRepo extends JpaRepository<Account, Long> {
    Boolean existsByEmail(String email);

    Boolean existsByNickName(String nickName);

    Optional<Account> findByEmail(String email);

    Optional<Account> findByNickName(String nickName);

    /**
     * 익명화 대상 = 탈퇴했고(soft delete) 유예기간이 지났으며 아직 익명화 안 된 계정.
     * threshold = now - graceDays. id 만 뽑아 각 건을 독립 트랜잭션으로 익명화한다.
     */
    @Query("select a.id from Account a " +
            "where a.isDeleted = true and a.anonymizedAt is null " +
            "and a.deletedAt is not null and a.deletedAt < :threshold")
    List<Long> findIdsToAnonymize(@Param("threshold") LocalDateTime threshold);
}
