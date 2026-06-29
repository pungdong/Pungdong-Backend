package com.diving.pungdong.notification;

/**
 * 알림 카테고리 — FCM Android **채널 라우팅** + 발송 정책(즉시성 / 마케팅 야간제한)의 단일 출처.
 *
 * <p>채널 5개(reservation/payment/chat/notice/marketing)는 **앱(FE)이 생성**한다(채널 importance·소리·
 * 유저 토글은 앱/OS 소유). BE 는 발송 메시지에 **channelId 만 지정**해 그 채널로 보낸다. iOS 는 채널
 * 개념이 없어 채널 무관 — 마케팅 차단은 인앱 동의 토글 + BE 게이팅이 대신한다.
 *
 * <p>채널표·정책·법적 근거는 docs/features/push.md.
 */
public enum NotificationCategory {
    // channelId, marketing(야간 08~21 제한 + 별도 수신동의), timeSensitive(즉시 전달 priority HIGH)
    RESERVATION("reservation", false, true),
    PAYMENT("payment", false, true),
    CHAT("chat", false, true),
    NOTICE("notice", false, false),       // 약관·개인정보 변경 등 필수 고지 (마케팅 아님)
    MARKETING("marketing", true, false);  // 광고성 — 야간 제한 + 별도 수신동의 + 자유 차단

    private final String channelId;
    private final boolean marketing;
    private final boolean timeSensitive;

    NotificationCategory(String channelId, boolean marketing, boolean timeSensitive) {
        this.channelId = channelId;
        this.marketing = marketing;
        this.timeSensitive = timeSensitive;
    }

    public String channelId() {
        return channelId;
    }

    /** 광고성 → 야간(21~08 KST) 전송 제한 + 마케팅 수신동의 게이트 대상. */
    public boolean isMarketing() {
        return marketing;
    }

    /** 거래성(예약/결제/채팅) → Android priority HIGH(절전 회피·즉시). 공지/마케팅은 NORMAL. */
    public boolean isTimeSensitive() {
        return timeSensitive;
    }
}
