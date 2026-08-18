package com.diving.pungdong.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;

/**
 * 내 글에 달린 댓글 한 줄 — {@code GET /community/posts/me/comments}.
 *
 * <p>프로필 브랜딩 카드의 "가장 최근 댓글" 미리보기(작성자 · 본문 2줄 · 글 제목 · 상대시각)와, 후속
 * "댓글 모아보기" 목록이 <b>같은 응답</b>을 쓴다. 미리보기가 1건만 그린다고 단건 스펙으로 줄이지
 * 않은 이유다 — 목록 화면이 붙을 때 계약도 클라이언트 코드도 바뀌지 않는다.
 *
 * <p><b>{@link CommunityCommentResponse}(스레드 한 줄)와 일부러 다른 타입이다.</b> 스레드는 "이 글의
 * 대화" 라 좋아요·답글·삭제 자리표시가 필요하고, 이쪽은 "어느 글에 달렸나" 가 필요하다. 한 타입에
 * 두 화면을 태우면 어느 쪽에서도 안 쓰는 필드가 절반이 되고, 삭제 댓글 규칙이 정반대라
 * (스레드는 자리를 남기고 여기선 아예 뺀다) 한 클래스 안에서 모순된다.
 *
 * <p>상대시각("3분 전")은 만들지 않는다 — UTC offset 을 실어 보내고 클라이언트가 렌더 시점에 만든다.
 */
@Getter
@Builder
@Relation(collectionRelation = "comments")
public class MyPostCommentResponse {

    private final Long id;

    /** 본문 원문. 2줄 클램프는 화면 폭을 아는 클라이언트가 한다. */
    private final String body;

    private final OffsetDateTime createdAt;

    /** 댓글을 단 사람. 피드 카드·스레드와 <b>같은 합성</b>(강사 여부·강의 수)을 쓴다. */
    private final CommunityAuthorResponse author;

    /** 어느 글에 달렸나 — 탭하면 이 글의 상세로 간다. */
    private final PostRef post;

    /**
     * 대댓글이면 부모 댓글 id, 최상위면 <b>키 생략</b>. 클라이언트가 "답글" 배지를 그리는 데 쓴다.
     * 원시 {@code long} 이 아니라 래퍼인 이유 — 0 을 내려보내면 "0번 댓글의 답글" 로 읽힌다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Long parentCommentId;

    /**
     * 댓글이 달린 글의 최소 식별 정보. 카드 전체({@link CommunityPostCardResponse})를 싣지 않는 이유:
     * 미리보기가 쓰는 건 제목·썸네일뿐인데 카드를 실으면 목록 크기만큼 좋아요·북마크·태그 집계가
     * 따라붙는다 — 화면에 그리지도 않는 숫자를 세느라 쿼리가 는다.
     */
    @Getter
    @Builder
    public static class PostRef {
        private final Long id;
        private final String title;
        /** 첫 사진. 사진 없는 글이면 {@code null} — 키는 남긴다(클라이언트가 자리표시자를 그린다). */
        private final String thumbnailUrl;
    }
}
