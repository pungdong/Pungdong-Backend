package com.diving.pungdong.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 같이가요 모집 정보. 게시물과 1:1 이라 PK 가 곧 post id 다. */
public interface CommunityPostMatchJpaRepo extends JpaRepository<CommunityPostMatch, Long> {

    /**
     * 피드 카드용 일괄 조회 — MATCH 글이 섞인 페이지에서 글마다 조회하지 않도록.
     * 미디어를 일괄 조회해 메모리에서 그룹핑하는 것과 같은 패턴이다.
     */
    @Query("select m from CommunityPostMatch m where m.postId in :postIds")
    List<CommunityPostMatch> findAllByPostIds(@Param("postIds") Collection<Long> postIds);

    void deleteByPostId(Long postId);
}
