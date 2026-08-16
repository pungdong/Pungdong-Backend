package com.diving.pungdong.chat;

/**
 * 채팅방 표시 상태 — <b>조회자 기준</b>으로 파생한다(저장값 아님).
 *
 * <p>디자인 3-state 아이콘과 1:1 이다: {@code HIDDEN}=아이콘 자체 미노출 / {@code ACTIVE}=활성(+unread 배지)
 * / {@code CLOSED}=disabled(읽기 전용, "회차 채팅이 종료됐어요").
 */
public enum ChatRoomState {

    /** 조회자가 그 세션의 참여자가 아니다(미결제 등). 방 행이 이미 있어도 이 사람에겐 안 보인다. */
    HIDDEN,

    /** 참여자 + 마감 전 + 세션 생존. 읽기 + 쓰기. */
    ACTIVE,

    /** 참여자지만 마감이 지났거나 세션이 소멸했다. <b>읽기 전용</b> — 조회(GET)는 200 이고 전송만 막는다. */
    CLOSED
}
