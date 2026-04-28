package com.diving.pungdong.domain.notification.event;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class LectureNotificationEvent {
    Long lectureId;
    @Singular
    List<Long> recipientAccountIds;
    String title;
    String body;
}
