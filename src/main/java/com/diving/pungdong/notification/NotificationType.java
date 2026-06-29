package com.diving.pungdong.notification;

public enum NotificationType {
    RESERVATION_CREATED(NotificationCategory.RESERVATION),
    RESERVATION_CANCELLED(NotificationCategory.RESERVATION),
    // ⚠️ 강사가 강의 팔로워에게 보내는 알림 — 현재 정보성으로 보고 NOTICE.
    // 홍보·혜택성(신규 강의/할인)으로 쓸 거면 MARKETING 으로 재분류(야간제한+수신동의) 필요. (FE/기획 확인)
    LECTURE_NOTIFICATION(NotificationCategory.NOTICE);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}
