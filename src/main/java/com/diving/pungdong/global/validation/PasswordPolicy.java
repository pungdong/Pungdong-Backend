package com.diving.pungdong.global.validation;

/**
 * 비밀번호 정책 — 여러 DTO(가입·비번변경·비번찾기)가 공유하는 크로스도메인 상수.
 *
 * <p>BE 는 <b>안전 하한(길이)만</b> 본다. 복잡도(영문·숫자 조합 등)는 FE 가 제출 게이팅으로 강제하고,
 * BE 는 그보다 느슨하게 둔다 — BE 가 FE 보다 엄격하면 FE 통과값이 400 나기 때문(입력검증 규칙).
 * bcrypt 72바이트 절삭을 감안해 상한을 64자로 둔다.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    public static final String MESSAGE = "비밀번호는 8자 이상 64자 이하여야 합니다.";

    private PasswordPolicy() {
    }
}
