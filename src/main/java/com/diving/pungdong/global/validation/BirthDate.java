package com.diving.pungdong.global.validation;

/**
 * 생년월일 {@code yyyyMMdd} 형식 — 정규화(구분자 제거) 후 적용하는 <b>크로스도메인 상수</b>.
 * 본인확인·계정 프로필 등이 공유한다. 월/일 범위까지만 보고 달력 정합(2월 31일 등)은 외부 기관에 맡긴다.
 */
public final class BirthDate {

    public static final String PATTERN = "^(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$";

    public static final String MESSAGE = "생년월일 형식이 올바르지 않습니다. (yyyyMMdd)";

    private BirthDate() {
    }
}
