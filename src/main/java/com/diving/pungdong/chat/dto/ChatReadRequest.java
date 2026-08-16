package com.diving.pungdong.chat.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

/**
 * 읽음 처리 요청. 멱등 — 이미 더 큰 값이 저장돼 있으면 <b>되감지 않는다</b>(전진만).
 * 폴링과 경합해도 안전하다.
 */
@Getter
@Setter
public class ChatReadRequest {

    @NotNull(message = "lastReadMessageId 는 필수입니다.")
    @PositiveOrZero(message = "lastReadMessageId 는 0 이상이어야 합니다.")
    private Long lastReadMessageId;
}
