package com.diving.pungdong.notification.fcm;

import com.diving.pungdong.notification.NotificationCategory;
import com.diving.pungdong.notification.fcm.FcmGateway.SendResult;
import com.google.firebase.messaging.FcmTestExceptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FirebaseFcmGateway} 의 FCM 예외 → {@link SendResult} 분류 계약. {@code @DisplayName} 을 위에서
 * 아래로 읽으면 스펙. 분류가 왜 중요한가: PERMANENT 는 워커가 <b>디바이스 토큰을 삭제</b>하므로,
 * 토큰이 멀쩡한데 프로젝트 설정(APNs 키 미등록 = THIRD_PARTY_AUTH_ERROR)이 원인인 실패를 PERMANENT 로
 * 두면 iOS 토큰이 전부 지워진다(2026-08-15 staging 진단, docs/features/push.md).
 */
class FirebaseFcmGatewayClassifyTest {

    private static final Map<String, String> DATA = Map.of("type", "X");

    private FirebaseFcmGateway gatewayThrowing(Exception e) throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenThrow(e);
        return new FirebaseFcmGateway(messaging);
    }

    private SendResult sendVia(FirebaseFcmGateway gw) {
        return gw.send("token", "t", "b", DATA, NotificationCategory.RESERVATION);
    }

    @Test
    @DisplayName("C1 UNREGISTERED(앱 삭제·토큰 만료)는 PERMANENT — 토큰 삭제 대상")
    void unregisteredIsPermanent() throws Exception {
        assertThat(sendVia(gatewayThrowing(FcmTestExceptions.withCode(MessagingErrorCode.UNREGISTERED, "gone"))))
                .isEqualTo(SendResult.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("C2 THIRD_PARTY_AUTH_ERROR(APNs 키 미등록 등 프로젝트 설정 문제)는 TRANSIENT — 토큰을 지우지 않는다")
    void thirdPartyAuthIsTransientNotTokenDeletion() throws Exception {
        assertThat(sendVia(gatewayThrowing(FcmTestExceptions.withCode(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR, "apns"))))
                .isEqualTo(SendResult.TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("C3 에러코드 없는 실패(예: JDK 가 본문을 버린 401)는 TRANSIENT — 재시도로 남긴다")
    void noCodeIsTransient() throws Exception {
        assertThat(sendVia(gatewayThrowing(FcmTestExceptions.withoutCode("Unexpected HTTP response with status: 401\nnull"))))
                .isEqualTo(SendResult.TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("C4 UNAVAILABLE(FCM 일시 장애)는 TRANSIENT")
    void unavailableIsTransient() throws Exception {
        assertThat(sendVia(gatewayThrowing(FcmTestExceptions.withCode(MessagingErrorCode.UNAVAILABLE, "down"))))
                .isEqualTo(SendResult.TRANSIENT_FAILURE);
    }
}
