package com.diving.pungdong.notification.fcm;

import com.diving.pungdong.notification.NotificationCategory;

import java.util.Map;

public interface FcmGateway {

    /** category 로 Android channelId·priority(+향후 iOS interruptionLevel)를 결정해 발송. */
    SendResult send(String token, String title, String body, Map<String, String> data,
                    NotificationCategory category);

    enum SendResult {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
