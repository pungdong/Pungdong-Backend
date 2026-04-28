package com.diving.pungdong.service.notification.fcm;

import java.util.Map;

public interface FcmGateway {

    SendResult send(String token, String title, String body, Map<String, String> data);

    enum SendResult {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
