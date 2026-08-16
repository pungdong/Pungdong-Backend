package com.diving.pungdong.chat.dto;

import com.diving.pungdong.chat.ChatMessageKind;
import com.diving.pungdong.chat.ChatParticipantRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 메시지 1건. {@link #id} 가 커서다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {

    /** 커서 겸용. */
    private Long id;

    /** {@code SYSTEM} 은 안내 pill — 같은 스트림에 섞여 온다. */
    private ChatMessageKind kind;

    /** {@code deleted} 면 툼스톤 문구로 대체된다. */
    private String text;

    private boolean deleted;

    /**
     * <b>instant</b> — ISO-8601 + 오프셋. 방 헤더의 {@code date}/{@code startTime} 은 civil 이라 변환
     * 금지지만 이건 변환 대상이다(성격이 다르다). 날짜 구분선·SYSTEM pill 의 날짜 접두는 FE 가 이 값으로
     * 합성한다.
     */
    private OffsetDateTime sentAt;

    /** {@code SYSTEM} 이면 null. */
    private Long senderId;
    /** "김수민 학생" — 말풍선 이름 라벨은 이것만 쓴다. */
    private String senderDisplayName;
    private String senderName;
    private ChatParticipantRole senderRole;

    /**
     * 요청자 기준으로 <b>서버가 계산</b>한다 — 말풍선 좌우 판정.
     *
     * <p>FE 가 {@code senderId === 내 accountId} 로 판정하려면 클라이언트가 자기 accountId 를 확실히
     * 알아야 하는데 그 경로가 플랫폼마다 다르다. 서버가 박으면 그 부류 버그가 통째로 사라진다.
     * {@code SYSTEM} 은 항상 false.
     */
    private boolean mine;

    /**
     * 전송 시 보낸 멱등키 <b>에코</b>. {@code SYSTEM} 은 null.
     *
     * <p>낙관적 UI 가 pending 말풍선을 서버 메시지와 잇는 유일한 키다. 특히 폴링({@code after})이 방금
     * 보낸 메시지를 다시 실어오므로, 에코가 없으면 낙관적 1건 + 폴링 1건으로 <b>두 번 렌더</b>된다.
     */
    private String clientMessageId;
}
