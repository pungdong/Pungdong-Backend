package com.diving.pungdong.identityverification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdentityVerificationJpaRepo extends JpaRepository<IdentityVerification, Long> {

    /** 계정의 가장 최근 본인확인 1건 — GET /identity-verifications/me 의 출처. id 단조증가라 최신=최대 id. */
    Optional<IdentityVerification> findTopByAccountIdOrderByIdDesc(Long accountId);
}
