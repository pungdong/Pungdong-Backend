package com.diving.pungdong.notification.fcm;

import com.diving.pungdong.notification.NotificationCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * firebase 비활성(또는 미설정)일 때의 no-op/로그 게이트웨이.
 *
 * <p>{@code firebase.enabled} 프로퍼티에 직접 키잉한다 — 예전엔
 * {@code @ConditionalOnMissingBean(name="firebaseFcmGateway")} 였으나, 컴포넌트 스캔에서
 * {@code @ConditionalOnMissingBean} 은 평가 순서가 보장되지 않아(다른 빈이 추가돼 스캔 순서가 바뀌면
 * 누락) prod 부팅이 깨졌다. {@code FirebaseFcmGateway} 와 같은 프로퍼티의 반대값으로 잠가 둘이
 * 상호배타 + 순서무관 — firebase.enabled 미설정/false → 이 빈, true → FirebaseFcmGateway.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingFcmGateway implements FcmGateway {

    @Override
    public SendResult send(String token, String title, String body, Map<String, String> data,
                           NotificationCategory category) {
        log.info("[fcm-stub] channel={} priority={} title={} body={} data={}",
                category.channelId(), category.isTimeSensitive() ? "HIGH" : "NORMAL", title, body, data);
        return SendResult.SUCCESS;
    }
}
