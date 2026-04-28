package com.diving.pungdong.repo;

import com.diving.pungdong.domain.account.Account;
import com.diving.pungdong.domain.account.FirebaseToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FirebaseTokenJpaRepo extends JpaRepository<FirebaseToken, Long> {

    Optional<FirebaseToken> findByToken(String token);

    void deleteByAccountAndToken(Account account, String token);
}
