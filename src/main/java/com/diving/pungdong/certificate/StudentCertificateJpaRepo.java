package com.diving.pungdong.certificate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentCertificateJpaRepo extends JpaRepository<StudentCertificate, Long> {

    /** 내 자격증 — 최근 취득 순. 개인 보유량이 한 자릿수라 페이지네이션 없음. */
    List<StudentCertificate> findByOwnerIdOrderByAcquiredAtDescIdDesc(Long ownerId);

    /**
     * 소유 검증까지 한 번에 — 없거나 남의 것이면 empty.
     * 호출처는 이걸 404 로 바꾼다(존재 숨김, 레포 anti-IDOR 규약).
     */
    Optional<StudentCertificate> findByIdAndOwnerId(Long id, Long ownerId);

    void deleteByOwnerId(Long ownerId);
}
