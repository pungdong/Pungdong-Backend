package com.diving.pungdong.branding;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BrandingPostJpaRepo extends JpaRepository<BrandingPost, Long> {

    /**
     * 공개 그리드 — 숨김 제외. <b>정렬을 쿼리에 박는다</b>(고정 먼저, 그 다음 최신순).
     *
     * <p>클라이언트가 보낸 {@code sort} 를 {@code Pageable} 에 태우지 않는 이유: 임의 필드로 정렬을 걸어
     * 내부 컬럼을 탐색하거나 인덱스 없는 정렬로 풀스캔을 유발할 수 있고, 무엇보다 <b>pinned-우선 규칙이
     * 깨진다.</b> 서비스가 정렬 없는 {@code PageRequest} 를 넘기므로 이 order by 가 그대로 쓰인다.
     */
    @Query("select p from BrandingPost p where p.branding.id = :brandingId "
            + "and p.isHidden = false and p.showOnProfile = true "
            + "order by p.pinned desc, p.createdAt desc, p.id desc")
    Page<BrandingPost> findPublicGrid(@Param("brandingId") Long brandingId, Pageable pageable);

    /**
     * 오너 그리드 — 숨김 포함(숨긴 걸 다시 켜려면 보여야 한다). 정렬 규칙은 동일.
     *
     * <p>{@code showOnProfile} 은 여기서도 건다. 커뮤니티에만 올린 글은 <b>오너에게도</b> 프로필 그리드에
     * 보이면 안 된다 — 숨김과 달리 "프로필에 올리지 않기로 한 글"이지 되돌릴 대상이 아니다.
     */
    @Query("select p from BrandingPost p where p.branding.id = :brandingId and p.showOnProfile = true "
            + "order by p.pinned desc, p.createdAt desc, p.id desc")
    Page<BrandingPost> findOwnerGrid(@Param("brandingId") Long brandingId, Pageable pageable);

    /** 오너 소유 확인용 — 남의 글이면 비어 있고, 호출처가 400(존재 숨김)으로 답한다. */
    @Query("select p from BrandingPost p where p.id = :postId and p.branding.account.id = :accountId")
    Optional<BrandingPost> findMine(@Param("postId") Long postId, @Param("accountId") Long accountId);

    /**
     * 공개 게시물 수 — 프로필 헤더 통계.
     *
     * <p>{@code showOnProfile} 을 함께 건다 — 안 걸면 커뮤니티에만 올린 글까지 세어 <b>헤더 숫자와
     * 그리드 타일 수가 어긋난다</b>(그리드는 프로필 글만 그린다).
     */
    long countByBranding_IdAndIsHiddenFalseAndShowOnProfileTrue(Long brandingId);
}
