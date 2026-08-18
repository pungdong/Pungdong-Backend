package com.diving.pungdong.community;

import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 피드 주변 탐색 지표 — 카테고리 4-up 그리드와 웹 sidebar 의 인기 태그·지금 뜨는 토픽.
 * <b>비로그인 가능</b>.
 *
 * <p>게시물 컨트롤러와 분리한 이유: 경로가 {@code /community/posts} 아래가 아니라 형제라
 * (그리드·sidebar 는 글 목록이 아니라 목록으로 <i>들어가는</i> 입구다) 같은 클래스에 두면
 * {@code @RequestMapping} 이 어긋난다.
 */
@RestController
@RequestMapping(value = "/community", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CommunityDiscoveryController {

    private final CommunityPostService postService;

    /** 카테고리별 이번 주 글 수. 4종 전부 온다 — 0개인 카테고리도 칸은 그려져야 한다. */
    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok().body(CollectionModel.of(postService.categoryCounts()));
    }

    /** 인기 태그. 기본 8개(웹 sidebar 가 그리는 개수). 집계 창은 최근 30일. */
    @GetMapping("/tags/popular")
    public ResponseEntity<?> popularTags(@RequestParam(required = false, defaultValue = "8") int limit) {
        return ResponseEntity.ok().body(CollectionModel.of(postService.popularTags(limit)));
    }

    /**
     * 지금 뜨는 토픽. 기본 5개(웹 sidebar 가 그리는 개수).
     *
     * <p>순위는 <b>인기순 피드와 같은 기준</b>(최근 7일 · 좋아요+댓글+북마크)이다.
     * 글이 모자라면 짧게 온다 — 카테고리 카운트처럼 빈 칸을 채우지 않는다.
     */
    @GetMapping("/topics/trending")
    public ResponseEntity<?> trendingTopics(@RequestParam(required = false, defaultValue = "5") int limit) {
        return ResponseEntity.ok().body(CollectionModel.of(postService.trendingTopics(limit)));
    }
}
