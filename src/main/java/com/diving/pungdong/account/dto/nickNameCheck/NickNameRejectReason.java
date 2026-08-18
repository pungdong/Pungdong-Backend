package com.diving.pungdong.account.dto.nickNameCheck;

/**
 * 닉네임을 쓸 수 없는 이유 — {@code GET /sign/check/nickName} 응답의 {@code reason}.
 *
 * <p>FE 가 사유별로 다른 문구를 띄우기 위한 값이다. 세 사유의 안내가 실제로 다르다:
 * 중복이면 "다른 이름을 써 주세요", 형식이면 "무엇이 틀렸는지", 예약어면 "쓸 수 없는 이름".
 */
public enum NickNameRejectReason {

    /** 이미 다른 계정이 쓰는 닉네임. */
    DUPLICATED,

    /** 길이·문자셋 위반 — {@code NickNamePolicy.PATTERN}. */
    FORMAT,

    /** 예약어(브랜드·운영자 사칭·라우트 충돌). 어떤 단어인지는 알려주지 않는다. */
    RESERVED
}
