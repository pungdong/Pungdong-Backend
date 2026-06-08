package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountJpaRepo extends JpaRepository<Account, Long> {
    Boolean existsByEmail(String email);

    Boolean existsByNickName(String nickName);

    Optional<Account> findByEmail(String email);

    Optional<Account> findByNickName(String nickName);
}
