package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 세션 단체 채팅 메시지 → <b>발신자를 뺀 참여자 전원</b>에게.
 *
 * <p>수신자 목록을 이벤트가 통째로 싣는다 — 리스너가 재조회하지 않게(기존 {@code LectureNotificationEvent}
 * 와 같은 fan-out 형태). 카테고리는 {@code CHAT}(채널 chat) 이고, 그 채널은 앱에 이미 만들어져 있다.
 *
 * <p>중복 전송(멱등 히트)에서는 발행하지 않는다 — 재시도 한 번에 참여자 전원이 알림을 두 번 받으면 안 된다.
 */
@Value
@Builder
public class ChatMessageEvent {

    List<Long> recipientAccountIds;

    /** 딥링크 좌표 — 앱은 이 값으로 채팅방에 바로 착지한다. */
    Long roomId;
    Long messageId;

    /** 알림 제목 조립용 스냅샷(방에 박제된 값). */
    String courseTitle;
    Integer roundIndex;

    String senderNickName;

    /** 본문 앞부분(공백 정규화 + 40자 컷). */
    String preview;
}
