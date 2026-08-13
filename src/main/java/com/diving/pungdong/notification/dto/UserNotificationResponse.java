package com.diving.pungdong.notification.dto;

import com.diving.pungdong.notification.NotificationType;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 알림함 한 줄.
 *
 * <p>⚠️ {@code @Relation} 이 <b>FE 와의 계약</b>이다 — 없으면 Spring HATEOAS 가 타입명에서 유도한
 * {@code userNotificationResponseList} 를 {@code _embedded} 키로 쓰고, FE 의
 * {@code unwrapHalPage(body, 'notifications')} 가 <b>에러 없이 빈 배열</b>을 받는다.
 *
 * <p>{@code data} 는 푸시 payload 의 data 와 같은 맵({@code notificationId}·{@code type} 포함)이라
 * 앱이 {@code routeFromPush(row.data)} 를 재조립 없이 호출한다.
 */
@Getter
@Builder
@Relation(collectionRelation = "notifications")
public class UserNotificationResponse {

    private final Long id;
    private final String notificationId;
    private final NotificationType type;
    private final String title;
    private final String body;
    private final Map<String, String> data;

    /** null = 미읽음. offset 포함(...Z)으로 직렬화된다. */
    private final OffsetDateTime readAt;

    private final OffsetDateTime createdAt;
}
