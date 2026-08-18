package com.diving.pungdong.block.dto;

import lombok.*;

import java.time.OffsetDateTime;

/**
 * 차단 목록 한 줄 — 설정의 "차단 관리" 화면.
 *
 * <p>강사 여부·강의 수 같은 합성 정보는 싣지 않는다. 차단 목록은 "누구를 차단했는지 확인하고 해제하는"
 * 화면이라 그 사람을 다시 소개할 이유가 없고, 실으면 목록 크기만큼 조회가 붙는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class BlockedAccountResponse {

    private String nickName;
    private String avatarUrl;
    /** 차단한 시각(UTC offset 포함). */
    private OffsetDateTime blockedAt;
}
