package com.diving.pungdong.community.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 댓글·대댓글 작성/수정. */
@Getter @Setter
@NoArgsConstructor
public class CommunityCommentRequest {

    @NotBlank(message = "댓글 내용을 입력해주세요.")
    @Size(max = 1000, message = "댓글은 1000자까지 쓸 수 있어요.")
    private String body;

    /**
     * 있으면 대댓글. <b>최상위 댓글만 부모가 될 수 있다</b> — 대댓글에 또 달면 400.
     *
     * <p>DB 로는 "부모의 부모가 없어야 한다"를 표현할 수 없어 서비스가 강제한다. 막지 않으면 스레드가
     * 무한히 깊어져 들여쓰기가 화면을 벗어나고, 디자인도 1-depth 로만 그려져 있다.
     *
     * <p>수정 시에는 무시된다 — 이미 달린 댓글의 부모를 바꾸는 건 스레드를 재배치하는 동작이라
     * 본문 수정과 섞을 일이 아니다.
     */
    private Long parentCommentId;
}
