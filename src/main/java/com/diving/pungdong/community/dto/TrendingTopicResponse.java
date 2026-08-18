package com.diving.pungdong.community.dto;

import com.diving.pungdong.branding.CommunityCategory;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

/**
 * 지금 뜨는 토픽 — 웹 우측 sidebar 의 순위 목록. 최근 7일 <b>참여 점수</b> 상위 게시물이다.
 *
 * <p>카드({@code CommunityPostCardResponse})가 아닌 <b>별도의 얇은 응답</b>인 이유: 사이드바는
 * 순위·제목·숫자만 그린다. 카드를 그대로 실으면 쓰지도 않을 썸네일·작성자·본문 발췌를 위해
 * 미디어·작성자 일괄 조회가 통째로 따라붙는다.
 */
@Getter
@Builder
@Relation(collectionRelation = "topics")
public class TrendingTopicResponse {

    /** 클릭 시 이동할 글. 목록 순서가 곧 순위다(서버가 정렬해서 준다). */
    private final Long postId;

    private final String title;

    /** 칩·아이콘용. 제목만으로는 어느 카테고리 글인지 알 수 없다. */
    private final CommunityCategory category;

    /**
     * 참여 점수 = <b>좋아요 + 댓글 + 북마크</b>. 화면의 오른쪽 숫자다.
     *
     * <p>"인기" 탭의 정렬 기준과 <b>같은 식</b>이다 — 다르면 같은 화면에서 1등이 둘로 갈린다.
     */
    private final long score;
}
