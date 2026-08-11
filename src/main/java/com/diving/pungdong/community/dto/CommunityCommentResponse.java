package com.diving.pungdong.community.dto;

import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 댓글 한 줄. 대댓글은 <b>1단까지</b>라 {@code replies} 안의 항목은 항상 빈 {@code replies} 를 갖는다.
 */
@Getter
@Builder
@Relation(collectionRelation = "comments")
public class CommunityCommentResponse {

    private final Long id;

    private final CommunityAuthorResponse author;

    /** 삭제된 댓글이면 원문 대신 안내 문구가 온다 — 아래 {@code deleted} 로 구분한다. */
    private final String body;

    /**
     * 삭제 표식. <b>대댓글이 달린 댓글은 지워도 자리가 남는다</b> — 물리 삭제하면 스레드 맥락이 끊기기
     * 때문이다. 클라이언트는 이 값으로 흐리게 처리하고 좋아요·답글 버튼을 숨긴다.
     */
    private final boolean deleted;

    private final OffsetDateTime createdAt;

    private final long likeCount;
    private final boolean likedByMe;

    /** 내 댓글이면 수정·삭제 메뉴를 노출한다. */
    private final boolean mine;

    /** 1단 대댓글. 시간 오름차순 — 스레드는 위에서 아래로 흐른다. */
    private final List<CommunityCommentResponse> replies;

    /**
     * 대댓글 수. 지금은 {@code replies.size()} 와 같다.
     *
     * <p>그래도 따로 주는 이유: 나중에 인라인 대댓글을 N개로 <b>자르더라도</b> 이 값은 전체 수를
     * 유지하므로 "답글 N개 더 보기" 를 만들 때 <b>계약도 클라이언트 코드도 바뀌지 않는다.</b>
     * 지금 상한을 정하지 않은 대신 전환 비용을 0 으로 만들어 둔 것이다.
     */
    private final int replyCount;
}
