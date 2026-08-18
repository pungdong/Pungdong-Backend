package com.diving.pungdong.account.dto.nickNameCheck;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * {@code GET /sign/check/nickName} 응답.
 *
 * <p><b>{@code exists} 와 {@code available} 이 둘 다 있는 이유</b>: {@code exists} 는 "누가 이미 쓰고
 * 있는가" 하나만 답하는 기존 필드(호환 유지)이고, 형식 위반·예약어는 <b>중복이 아닌데도 못 쓰는</b>
 * 경우라 {@code exists:false} 로 나온다. 그걸 "사용 가능"으로 읽으면 FE 가 초록불을 켜 놓고 가입에서
 * 400 을 맞는다. 그래서 최종 판정은 {@code available} 이고, 사유는 {@code reason} 이다.
 *
 * <p>형식 위반·예약어를 400 이 아니라 200 으로 답하는 건 레포 규칙이다 — 중복확인은 <b>질의</b>라
 * 기대된 부정 답도 정상 응답(200 + 결과 필드)으로 준다.
 */
@Data
@AllArgsConstructor
@Builder
public class NickNameResult {

    /** 이미 쓰는 계정이 있는가. (형식 위반·예약어는 여기선 {@code false} — {@code available} 을 볼 것.) */
    private Boolean exists;

    /** 최종 판정 — 이 닉네임으로 가입/변경이 통과하는가. */
    private Boolean available;

    /** {@code available:false} 일 때의 사유. 사용 가능하면 {@code null}. */
    private NickNameRejectReason reason;
}
