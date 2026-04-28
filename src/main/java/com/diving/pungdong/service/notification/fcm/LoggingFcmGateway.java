package com.diving.pungdong.service.notification.fcm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConditionalOnMissingBean(name = "firebaseFcmGateway")
public class LoggingFcmGateway implements FcmGateway {

    @Override
    public SendResult send(String token, String title, String body, Map<String, String> data) {
        log.info("[fcm-stub] token=*** title={} body={} data={}", title, body, data);
        return SendResult.SUCCESS;
    }
}
