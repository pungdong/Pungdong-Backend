package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.FirebaseToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FirebaseTokenJpaRepo extends JpaRepository<FirebaseToken, Long> {

    Optional<FirebaseToken> findByToken(String token);

    List<FirebaseToken> findByAccount_Id(Long accountId);

    void deleteByToken(String token);

    void deleteByAccountAndToken(Account account, String token);

    /** 탈퇴 익명화 시 해당 계정의 모든 푸시 토큰(기기 식별자) 제거. */
    void deleteByAccount_Id(Long accountId);
}
