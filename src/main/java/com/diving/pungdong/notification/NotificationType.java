package com.diving.pungdong.notification;

public enum NotificationType {
    RESERVATION_CREATED(NotificationCategory.RESERVATION),
    RESERVATION_CANCELLED(NotificationCategory.RESERVATION),
    // 강사가 "그 회차(schedule)를 예약한 수강생들"에게 보내는 운영 메시지(장소·준비물·우천취소,
    // "현장에서 오렌지 모자 쓰고 있어요" 등). 수강/예약 관련 거래성이라 reservation(HIGH) — 누락되면
    // 현장에서 곤란. 마케팅 아님(이미 예약 맺은 관계 = 광고규제 대상 아님).
    LECTURE_NOTIFICATION(NotificationCategory.RESERVATION),

    /**
     * 커뮤니티 — 내 글에 댓글이 달렸거나 내 댓글에 답글이 달렸을 때.
     *
     * <p><b>카테고리가 {@code NOTICE} 인 이유</b>: 거래성(RESERVATION/PAYMENT)이 아니고 광고성도 아니다.
     * 무엇보다 <b>Android 채널은 앱이 생성</b>하므로 커뮤니티 전용 채널을 새로 만들면 그 알림이
     * <b>모바일 릴리스에 묶인다</b> — 기존 {@code notice} 채널을 재사용하면 앱 변경 없이 나간다.
     *
     * <p>⚠️ {@code NotificationOutbox.type} 이 {@code varchar(32)} 라 enum 이름이 32자를 넘으면 안 된다.
     * 이 값은 25자.
     *
     * <p>좋아요 알림은 만들지 않는다 — 빈도가 높아 소음이 되고 디자인 근거도 없다.
     */
    COMMUNITY_COMMENT(NotificationCategory.NOTICE);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}
