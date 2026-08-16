package com.diving.pungdong.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 참여자 목록 — 헤더 부제 확장(전체 목록 시트)용. 방 상세에도 같은 내용이 실려 있다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatParticipantsResponse {

    /** 현재 참여자만(이탈자 제외). */
    private List<ChatParticipantResponse> participants;

    private int participantCount;
}
