package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 커뮤니티에 댓글이 달렸다 — 내 글에 댓글, 또는 내 댓글에 답글.
 *
 * <p><b>수신자는 발행 시점에 이미 정해져 있다.</b> 이 이벤트를 만든 쪽이 "글 작성자냐 댓글 작성자냐"를
 * 판단해 {@code recipientAccountId} 를 채운다 — 알림 파이프라인은 누가 받아야 하는지 모르는 게 맞다.
 *
 * <p><b>자기 자신에게는 발행하지 않는다.</b> 파이프라인에 자기알림 필터가 없어서 <b>발행 지점에서</b>
 * 걸러야 한다(내 글에 내가 댓글 다는 건 흔한 동작이다).
 */
@Value
@Builder
public class CommunityCommentEvent {

    /** 알림을 받을 사람 — 글 작성자(댓글) 또는 부모 댓글 작성자(답글). */
    Long recipientAccountId;

    Long postId;

    /** 방금 달린 댓글 id — 클라이언트가 딥링크에서 해당 댓글로 스크롤하는 데 쓴다. */
    Long commentId;

    /** 댓글 단 사람의 닉네임 — 알림 본문에 들어간다. */
    String actorNickName;

    /** 대상 글 제목. V31 부터 항상 있지만, 알림 문구 쪽에 빈 값 폴백을 남겨뒀다. */
    String postTitle;

    /** 답글이면 true — 알림 문구가 "댓글" 이 아니라 "답글" 이 된다. */
    boolean reply;
}
