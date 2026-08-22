package com.diving.pungdong.certificate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertificateReviewJpaRepo extends JpaRepository<CertificateReview, Long> {

    /** 자격증 1장의 살아있는 검수 요청 — ADDITIONAL/RE_VERIFY 는 자격증당 PENDING 최대 1건. */
    Optional<CertificateReview> findFirstByCertificateIdAndStatus(Long certificateId, CertificateReviewStatus status);

    /** 신청 1건의 살아있는 NEW 행. */
    Optional<CertificateReview> findFirstByApplicationIdAndStatus(Long applicationId, CertificateReviewStatus status);

    List<CertificateReview> findByCertificateId(Long certificateId);

    Page<CertificateReview> findAllByStatus(CertificateReviewStatus status, Pageable pageable);

    long countByStatus(CertificateReviewStatus status);

    void deleteByCertificateId(Long certificateId);

    void deleteByAccountId(Long accountId);
}
