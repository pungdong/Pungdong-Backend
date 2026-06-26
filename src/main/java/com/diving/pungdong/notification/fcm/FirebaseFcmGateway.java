package com.diving.pungdong.notification.fcm;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * firebase 활성 시 실제 FCM 전송 게이트웨이. {@code firebase.enabled=true} 일 때만 — 그 경우
 * {@link com.diving.pungdong.global.config.FirebaseConfig} 가 {@link FirebaseMessaging} 빈을 만든다.
 * {@code @ConditionalOnBean} 대신 프로퍼티 키잉으로 바꾼 이유는 {@link LoggingFcmGateway} 주석 참고.
 */
@Slf4j
@Component("firebaseFcmGateway")
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
@RequiredArgsConstructor
public class FirebaseFcmGateway implements FcmGateway {

    private final FirebaseMessaging firebaseMessaging;

    @Override
    public SendResult send(String token, String title, String body, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build());
        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }
        try {
            firebaseMessaging.send(builder.build());
            return SendResult.SUCCESS;
        } catch (FirebaseMessagingException e) {
            return classify(e);
        }
    }

    private SendResult classify(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();
        if (code == null) {
            log.warn("FCM send failed without error code: {}", e.getMessage());
            return SendResult.TRANSIENT_FAILURE;
        }
        switch (code) {
            case UNREGISTERED:
            case INVALID_ARGUMENT:
            case SENDER_ID_MISMATCH:
            case THIRD_PARTY_AUTH_ERROR:
                log.info("FCM permanent failure ({}): {}", code, e.getMessage());
                return SendResult.PERMANENT_FAILURE;
            case INTERNAL:
            case UNAVAILABLE:
            case QUOTA_EXCEEDED:
            default:
                log.info("FCM transient failure ({}): {}", code, e.getMessage());
                return SendResult.TRANSIENT_FAILURE;
        }
    }
}
