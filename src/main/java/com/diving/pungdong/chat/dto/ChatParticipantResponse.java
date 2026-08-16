package com.diving.pungdong.chat.dto;

import com.diving.pungdong.chat.ChatParticipantRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 참여자 요약. <b>{@code accountId} 를 반드시 싣는다</b> — 이름만 있으면 동명이인에서 무너진다
 * (기존 {@code ApplicantSummaryResponse} 가 그 형태라 재사용하지 않았다).
 *
 * <p>{@code displayName} 은 <b>BE 가 합성</b>한다("김수민 학생"). FE 합성으로 두면 web/mobile 사본 2벌이
 * 어긋나는데, 이 레포는 그 부류가 실제로 반복돼 왔다. {@code name}/{@code role} 도 함께 주므로 역할별
 * 스타일링·정렬은 그대로 가능하다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatParticipantResponse {

    private Long accountId;

    /** 라벨은 이것만 쓴다 — "김수민 학생" / "김민지 강사". */
    private String displayName;

    /** 닉네임 원본(실명 미수집). 정렬·검색용. */
    private String name;

    /** 아바타 첫 글자. */
    private String initials;

    private ChatParticipantRole role;
}
