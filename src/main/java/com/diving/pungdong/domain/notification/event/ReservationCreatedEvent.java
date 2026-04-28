package com.diving.pungdong.domain.notification.event;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReservationCreatedEvent {
    Long instructorAccountId;
    Long studentAccountId;
    Long lectureId;
    Long scheduleId;
    String studentNickname;
    String lectureTitle;
}
