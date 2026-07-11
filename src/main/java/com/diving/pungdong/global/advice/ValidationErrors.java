package com.diving.pungdong.global.advice;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/**
 * Bean Validation(`@Valid`) 실패를 사용자용 메시지로 변환하는 헬퍼.
 *
 * <p>형식 오류는 <b>어느 필드가 왜 틀렸는지</b>를 응답 {@code msg} 로 노출한다 — 형식 규칙은 이미
 * 공개된 계약(types.ts·FE)이라 숨겨 지킬 secret 이 없다(oracle 아님, 로그인 enumeration 숨김과 다름).
 * 컨트롤러가 이 값을 {@code BadRequestException} 으로 넘기면 {@code ExceptionAdvice.badRequest} 가
 * 그대로 {@code msg} 로 내보낸다. 상세 원칙은 루트 CLAUDE.md "Validate input shape".
 */
public final class ValidationErrors {

    private ValidationErrors() {
    }

    /** 첫 필드 검증 메시지. 필드 에러가 없으면 {@code null} → advice 가 일반 {@code badRequest.msg} 로 폴백. */
    public static String firstMessage(BindingResult result) {
        FieldError fieldError = result.getFieldError();
        return fieldError != null ? fieldError.getDefaultMessage() : null;
    }
}
