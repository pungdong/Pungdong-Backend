package com.diving.pungdong.chat.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 메시지 전송 요청. */
@Getter
@Setter
public class ChatSendRequest {

    @NotBlank(message = "메시지를 입력해 주세요.")
    @Size(max = 1000, message = "메시지는 1000자까지 보낼 수 있어요.")
    private String text;

    /**
     * 전송 멱등키. 같은 값으로 다시 보내면 새 메시지를 만들지 않고 기존 메시지를 200 으로 돌려준다.
     *
     * <p>⚠️ <b>UUID 포맷을 강제하지 않는다</b>({@code @Pattern} 없음). RN(Hermes)은 WebCrypto 를 싣지 않아
     * {@code crypto.randomUUID()} 를 못 쓰고, 강제하면 모바일에 네이티브 의존성이 붙는다. UNIQUE 가
     * {@code (sender_account_id, client_message_id)} 라 사용자 간 충돌은 애초에 무의미하고, 한 사용자의
     * 짧은 재시도 창 안에서만 유일하면 충분하다.
     *
     * <p>FE 규약: <b>전송 버튼을 누른 시점에 1회 생성</b>하고 재시도(자동·수동 무관)엔 같은 값을 재사용한다.
     * 재시도마다 새로 만들면 멱등이 의미를 잃는다.
     */
    @NotBlank(message = "clientMessageId 는 필수입니다.")
    @Size(max = 64, message = "clientMessageId 는 64자를 넘을 수 없습니다.")
    private String clientMessageId;
}
