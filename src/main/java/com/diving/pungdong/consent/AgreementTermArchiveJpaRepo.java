package com.diving.pungdong.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgreementTermArchiveJpaRepo extends JpaRepository<AgreementTermArchive, Long> {

    /** 이미 박제된 약관 버전인지 — 있으면 재사용, 없으면 freeze. */
    Optional<AgreementTermArchive> findByTermKeyAndVersion(String termKey, String version);
}
