package com.diving.pungdong.branding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BrandingPostMediaJpaRepo extends JpaRepository<BrandingPostMedia, Long> {

    /**
     * 그리드 카드용 미디어 <b>일괄</b> 조회 — 카드마다 {@code post.getMedia()} 를 건드리면 페이지 크기만큼
     * 쿼리가 나간다(N+1). 한 번에 모아 메모리에서 그룹핑한다({@code PublicInstructorService} 와 같은 패턴).
     */
    @Query("select m from BrandingPostMedia m where m.post.id in :postIds "
            + "order by m.post.id asc, m.sortOrder asc")
    List<BrandingPostMedia> findAllByPostIds(@Param("postIds") Collection<Long> postIds);
}
