package com.diving.pungdong.certificate;

import com.diving.pungdong.course.CertLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StudentCertificateJpaRepo extends JpaRepository<StudentCertificate, Long> {

    /**
     * 내 자격증 — 최근 취득 순. 개인 보유량이 한 자릿수라 페이지네이션 없음.
     * 취득일 null(백필 행)은 DESC 정렬에서 MySQL·H2 모두 뒤로 간다.
     */
    List<StudentCertificate> findByOwnerIdOrderByAcquiredAtDescIdDesc(Long ownerId);

    /**
     * 소유 검증까지 한 번에 — 없거나 남의 것이면 empty.
     * 호출처는 이걸 404 로 바꾼다(존재 숨김, 레포 anti-IDOR 규약).
     */
    Optional<StudentCertificate> findByIdAndOwnerId(Long id, Long ownerId);

    /** 소유자의 여러 id 를 한 번에(남의 id 는 빠진다) — 강사 신청 첨부 검증용. */
    List<StudentCertificate> findByIdInAndOwnerId(Collection<Long> ids, Long ownerId);

    /**
     * 한 종목에서 "살아있는 검증"(VERIFIED/PENDING) 강사레벨 자격증 수 — Rule C 의 "마지막 한 장" 판정.
     */
    @Query("select count(c) from StudentCertificate c where c.owner.id = :ownerId and c.disciplineCode = :disciplineCode "
            + "and c.level in :levels and c.verification.status in :statuses")
    long countLive(@Param("ownerId") Long ownerId, @Param("disciplineCode") String disciplineCode,
                   @Param("levels") Collection<CertLevel> levels,
                   @Param("statuses") Collection<CertificateVerificationStatus> statuses);

    /** 한 종목의 특정 상태 강사레벨 자격증 — 승인 sweep(NONE → PENDING) / 제출 자동첨부용. */
    @Query("select c from StudentCertificate c where c.owner.id = :ownerId and c.disciplineCode = :disciplineCode "
            + "and c.level in :levels and c.verification.status = :status order by c.id asc")
    List<StudentCertificate> findByOwnerDisciplineLevelsAndStatus(@Param("ownerId") Long ownerId,
                                                                  @Param("disciplineCode") String disciplineCode,
                                                                  @Param("levels") Collection<CertLevel> levels,
                                                                  @Param("status") CertificateVerificationStatus status);

    /**
     * 여러 계정의 종목별 "살아있는 검증" 강사레벨 자격증 수 — {@code [accountId, disciplineCode, count]}.
     * 어드민 큐 목록의 "검증 자격증 0건" 플래그(행마다 세지 않는다).
     */
    @Query("select c.owner.id, c.disciplineCode, count(c) from StudentCertificate c where c.owner.id in :accountIds "
            + "and c.level in :levels and c.verification.status in :statuses group by c.owner.id, c.disciplineCode")
    List<Object[]> countLiveByAccountIds(@Param("accountIds") Collection<Long> accountIds,
                                         @Param("levels") Collection<CertLevel> levels,
                                         @Param("statuses") Collection<CertificateVerificationStatus> statuses);

    /** 공개 인증마크의 출처 — 한 계정의 VERIFIED 자격증 전부(종목 무관). */
    @Query("select c from StudentCertificate c where c.owner.id = :ownerId "
            + "and c.verification.status = com.diving.pungdong.certificate.CertificateVerificationStatus.VERIFIED "
            + "order by c.disciplineCode asc, c.id asc")
    List<StudentCertificate> findVerifiedByOwner(@Param("ownerId") Long ownerId);

    /**
     * 여러 계정의 <b>한 종목</b> VERIFIED 자격증 단체 코드 — {@code [accountId, organizationCode]} 쌍.
     * 강사 둘러보기 카드의 단체 칩용(카드마다 조회하지 않고 한 번에). 중복 제거는 호출부.
     */
    @Query("select c.owner.id, c.organizationCode from StudentCertificate c where c.owner.id in :accountIds "
            + "and c.disciplineCode = :disciplineCode "
            + "and c.verification.status = com.diving.pungdong.certificate.CertificateVerificationStatus.VERIFIED")
    List<Object[]> findVerifiedOrganizationCodesByAccountIds(@Param("accountIds") Collection<Long> accountIds,
                                                             @Param("disciplineCode") String disciplineCode);

    void deleteByOwnerId(Long ownerId);
}
