package com.diving.pungdong.block.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 차단 요청 — 대상은 <b>닉네임</b>이다.
 *
 * <p>계정 id 를 받지 않는 이유: 순차 id 를 클라이언트 계약에 노출하면 증가시켜 전수 조회하는 길이 열린다
 * (루트 CLAUDE.md anti-IDOR). 공개 프로필이 이미 {@code GET /instructors/{nickName}} 으로 닉네임을
 * 식별자로 쓰고 있고, 커뮤니티 카드·댓글의 작성자 DTO 도 닉네임을 싣는다 — 클라이언트가 이미 들고 있는 값이다.
 *
 * <p>닉네임은 PII 가 아니다(사용자가 고른 공개 표시 핸들) — 그래서 POST 본문이 아니어도 규칙 위반은
 * 아니지만, 차단은 쓰기라 본문으로 받는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class BlockRequest {

    @NotBlank(message = "차단할 사용자를 지정해 주세요.")
    @Size(max = 30, message = "닉네임이 너무 깁니다.")
    private String nickName;
}
