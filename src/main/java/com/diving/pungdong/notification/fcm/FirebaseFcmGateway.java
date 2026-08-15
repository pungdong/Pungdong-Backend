package com.diving.pungdong.notification.fcm;

import com.diving.pungdong.notification.NotificationCategory;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
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
    public SendResult send(String token, String title, String body, Map<String, String> data,
                           NotificationCategory category) {
        // Android: category → channelId(앱이 만든 채널로 라우팅) + priority(거래성=HIGH 절전회피·즉시,
        // 공지/마케팅=NORMAL). 채널 importance(heads-up/소리)는 앱 소유. iOS interruptionLevel 은 iOS 활성화 때.
        AndroidConfig androidConfig = AndroidConfig.builder()
                .setPriority(category.isTimeSensitive()
                        ? AndroidConfig.Priority.HIGH
                        : AndroidConfig.Priority.NORMAL)
                .setNotification(AndroidNotification.builder()
                        .setChannelId(category.channelId())
                        .build())
                .build();
        // iOS: interruption-level(마케팅=passive/거래=time-sensitive/공지=active)을 aps 에 실음.
        // aps 만 따로 두면 alert 가 누락될 수 있어 title/body 를 aps.alert 로 함께 넣어 self-contained.
        // iOS 비활성 동안엔 휴면(APNs 미발송). time-sensitive 의 실제 효과는 네이티브 엔타이틀먼트 필요.
        ApnsConfig apnsConfig = ApnsConfig.builder()
                .setAps(Aps.builder()
                        .setAlert(ApsAlert.builder().setTitle(title).setBody(body).build())
                        .putCustomData("interruption-level", category.apnsInterruptionLevel())
                        .build())
                .build();
        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setAndroidConfig(androidConfig)
                .setApnsConfig(apnsConfig);
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
            // ⚠️ 401 은 여기로 온다. JDK HttpURLConnection 이 스트리밍 POST + 401 조합에서 응답 본문을 버려
            // SDK 가 에러코드를 못 읽는다(2026-08-15 staging 재현). 서버 자격증명(WIF)보다 먼저 "수신 토큰이 iOS 인데
            // Firebase 에 APNs 키가 없나"(= 본래 THIRD_PARTY_AUTH_ERROR)를 의심할 것 — docs/features/push.md.
            log.warn("FCM send failed without error code (HTTP {}): {}",
                    e.getHttpResponse() == null ? "?" : e.getHttpResponse().getStatusCode(), e.getMessage());
            return SendResult.TRANSIENT_FAILURE;
        }
        switch (code) {
            case UNREGISTERED:
            case INVALID_ARGUMENT:
            case SENDER_ID_MISMATCH:
                log.info("FCM permanent failure ({}): {}", code, e.getMessage());
                return SendResult.PERMANENT_FAILURE;
            case THIRD_PARTY_AUTH_ERROR:
                // APNs 키/인증서 미등록 등 *Firebase 프로젝트 설정* 문제 — 토큰이 죽은 게 아니다. PERMANENT 로 두면
                // 워커가 iOS 토큰을 전부 삭제해 버리므로(설정 고치면 도달할 알림들까지 유실) 재시도로 남긴다.
                log.warn("FCM third-party auth failure (APNs/Web push 자격 확인 필요) ({}): {}", code, e.getMessage());
                return SendResult.TRANSIENT_FAILURE;
            case INTERNAL:
            case UNAVAILABLE:
            case QUOTA_EXCEEDED:
            default:
                log.info("FCM transient failure ({}): {}", code, e.getMessage());
                return SendResult.TRANSIENT_FAILURE;
        }
    }
}
