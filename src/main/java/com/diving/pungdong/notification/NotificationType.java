package com.diving.pungdong.notification;

public enum NotificationType {
    RESERVATION_CREATED(NotificationCategory.RESERVATION),
    RESERVATION_CANCELLED(NotificationCategory.RESERVATION),
    // 강사가 "그 회차(schedule)를 예약한 수강생들"에게 보내는 운영 메시지(장소·준비물·우천취소,
    // "현장에서 오렌지 모자 쓰고 있어요" 등). 수강/예약 관련 거래성이라 reservation(HIGH) — 누락되면
    // 현장에서 곤란. 마케팅 아님(이미 예약 맺은 관계 = 광고규제 대상 아님).
    LECTURE_NOTIFICATION(NotificationCategory.RESERVATION);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}
