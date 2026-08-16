package com.diving.pungdong.chat.dto;

import com.diving.pungdong.chat.ChatRoomState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차 카드·슬롯 상세에 실리는 채팅 진입 정보.
 *
 * <p><b>항상 non-null 로 내린다.</b> "안 보임" 을 표현하는 길이 둘이면({@code chat == null} 과
 * {@code state == HIDDEN}) FE 호출부가 매번 둘 다 검사해야 하고, 한쪽만 검사한 곳이 조용히 버그가 된다
 * (안 보여야 할 채팅 버튼이 보인다). 채팅 개념이 없는 회차도 {@link #hidden()} 을 준다.
 *
 * <p><b>{@code roomId} 는 FE 가 만들지 않는다.</b> 오늘 이 값은 세션 id 와 같은 숫자지만 그건 BE 내부
 * 구현이다 — FE 는 응답으로 받은 값만 되돌려주고 {@code sessionId} 와 비교하지 않는다. 그래야 나중에
 * 방 키를 슬롯 id 에서 분리해도 FE 가 안 깨진다. {@code HIDDEN} 일 때 null 인 것이 그 규율의 안전망이다
 * — {@code sessionId} 로 잘못 구성했다면 HIDDEN 회차에서도 값이 남아 있어 티가 난다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundChatState {

    private ChatRoomState state;

    /** {@code HIDDEN} 이면 null — navigate 자체를 막는다. */
    private Long roomId;

    /** {@code HIDDEN} 이면 0. */
    private int unreadCount;

    /** 참여자가 아니거나 채팅 개념이 없는 회차(슬롯 미배정 등). */
    public static RoundChatState hidden() {
        return RoundChatState.builder()
                .state(ChatRoomState.HIDDEN)
                .roomId(null)
                .unreadCount(0)
                .build();
    }

    public static RoundChatState of(ChatRoomState state, Long roomId, int unreadCount) {
        if (state == ChatRoomState.HIDDEN) {
            return hidden();
        }
        return RoundChatState.builder().state(state).roomId(roomId).unreadCount(unreadCount).build();
    }
}
