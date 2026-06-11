package com.diving.pungdong.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentJpaRepo extends JpaRepository<Consent, Long> {

    /** 내 동의 이력 (최신순). */
    List<Consent> findByAccountIdOrderByIdDesc(Long accountId);
}
