package com.google.firebase.messaging;

import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;

/**
 * 테스트 전용 — {@link FirebaseMessagingException} 의 팩토리가 패키지 프라이빗이라 같은 패키지에서
 * 만들어 준다. {@code FirebaseFcmGateway.classify} 분기 검증에 쓴다.
 */
public final class FcmTestExceptions {
    private FcmTestExceptions() {}

    /** FCM 이 {@code MessagingErrorCode} 를 돌려준 경우. */
    public static FirebaseMessagingException withCode(MessagingErrorCode code, String message) {
        return FirebaseMessagingException.withMessagingErrorCode(
                new FirebaseException(ErrorCode.UNKNOWN, message, null), code);
    }

    /** 에러코드 없는 실패 — 401 처럼 JDK 가 응답 본문을 버려 코드를 못 읽은 경우. */
    public static FirebaseMessagingException withoutCode(String message) {
        return FirebaseMessagingException.withMessagingErrorCode(
                new FirebaseException(ErrorCode.UNAUTHENTICATED, message, null), null);
    }
}
