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
    COMMUNITY_COMMENT(NotificationCategory.NOTICE),

    // ── 수강(enrollment) 흐름 ──────────────────────────────────────────────
    // 전부 기존 reservation 채널을 쓴다. 새 Android 채널을 만들면 그 알림이 앱 릴리스에 묶이기
    // 때문(채널 생성은 앱 책임) — COMMUNITY_COMMENT 가 NOTICE 를 재사용한 것과 같은 이유.

    /** 강사 수락 → 학생. 학생이 24h 시계를 들고 기다리던 답이라 최우선. */
    ENROLLMENT_ACCEPTED(NotificationCategory.RESERVATION),
    /** 강사 거절 → 학생. body 에 전액 환불 안내를 포함하므로 별도 환불 알림을 보내지 않는다. */
    ENROLLMENT_REJECTED(NotificationCategory.RESERVATION),
    /** 강사 일정 제안 → 학생. 제안엔 6h 만료가 걸려 있어 지연이 곧 실패다. */
    ENROLLMENT_SLOTS_PROPOSED(NotificationCategory.RESERVATION),
    /**
     * TTL 만료 → 학생. 미결제 12h(환불 없음) / 결제완료 무응답 24h(전액 자동환불) 두 갈래를
     * body 로 구분한다. 통보 없이 신청이 사라지는 걸 막는 게 목적.
     */
    ENROLLMENT_EXPIRED(NotificationCategory.RESERVATION),
    /** 새 수강신청 → 강사. */
    ENROLLMENT_SUBMITTED(NotificationCategory.RESERVATION),
    /** 회차 완료 → 학생. 리뷰 유도 훅. */
    ROUND_COMPLETED(NotificationCategory.RESERVATION),

    // ── 결제(payment) 흐름 ────────────────────────────────────────────────
    // payment 채널은 이미 앱에 생성돼 있는데 아무도 안 쓰던 빈 채널이다 — 신설이 아니라 첫 사용이라
    // 앱 변경이 필요 없다.

    /** 결제 완료 → 학생. */
    PAYMENT_COMPLETED(NotificationCategory.PAYMENT),
    /**
     * 환불 완료 → 학생. <b>학생이 직접 요청한 환불에만</b> 발행한다 — 거절·만료로 인한 자동환불은
     * 그쪽 알림 body 가 이미 환불을 안내하므로 2건이 연속으로 가면 소음이다(2026-08-14 사용자 결정).
     */
    REFUND_COMPLETED(NotificationCategory.PAYMENT),

    // ── 채팅(chat) 흐름 ──────────────────────────────────────────────────
    // chat 채널도 payment 와 같다 — 앱에 이미 생성돼 있고 아무도 안 쓰던 채널이라 첫 사용이며 앱 변경이
    // 필요 없다. NotificationCategory.CHAT 도 미리 만들어져 있었다(timeSensitive=true).

    /**
     * 세션 단체 채팅 새 메시지 → <b>발신자를 뺀 참여자 전원</b>(강사 + 결제완료 수강생).
     *
     * <p>유일하게 <b>허브가 아니라 채팅방으로 바로 착지</b>하는 타입이다. 다른 타입이 파라미터 없는 허브로
     * 가는 건 v1 에 그 화면들이 없었기 때문이지 강한 규약이어서가 아니었고, 채팅은 목록 메뉴 자체가 없어
     * 방으로 못 가면 알림이 쓸모가 없다(2026-08-16 사용자 결정). 착지 실패 시 폴백은 허브다.
     */
    CHAT_MESSAGE(NotificationCategory.CHAT);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}
