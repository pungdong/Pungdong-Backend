package com.diving.pungdong.chat;

/**
 * 메시지 종류. {@code SYSTEM} 은 <b>같은 스트림에 섞여</b> 내려간다(별도 배열 아님) — 디자인의 안내 pill 이
 * 본문 리스트 안에 위치하기 때문.
 *
 * <p>⚠️ SYSTEM 문구에 <b>날짜를 넣지 않는다</b>. 디자인은 "12/3 (화) · 회차 채팅방이 열렸어요" 지만 BE 는
 * {@code "회차 채팅방이 열렸어요"} 만 주고 날짜 접두는 FE 가 {@code sentAt} 으로 합성한다 — BE 문구에
 * 레이아웃·포맷을 종속시키지 않는다(DS 규약의 "BE 메시지에 개행 박지 말 것" 과 같은 부류).
 */
public enum ChatMessageKind {

    /** 사람이 보낸 메시지. {@code senderAccountId} 가 있다. */
    USER,

    /** 시스템 안내. {@code senderAccountId} 가 null 이고 {@code mine} 은 항상 false. */
    SYSTEM
}
