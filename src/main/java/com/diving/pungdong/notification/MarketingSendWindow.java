package com.diving.pungdong.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 광고성(마케팅) 알림 **야간 전송 제한**.
 *
 * <p>정보통신망법 제50조: 광고성 정보를 **21:00~익일 08:00** 에 전송하려면 *별도(야간) 수신동의* 필요.
 * 우리는 야간 동의를 받지 않으므로 허용 윈도우 = **08:00~21:00 (KST)**. 야간에 만들어진 마케팅 알림은
 * **다음 08:00 KST 로 미뤄 큐잉**한다 — outbox 의 {@code nextAttemptAt} 을 그 시각으로 두면 디스패처가
 * 그때부터 {@code createdAt} 순서대로 발송(별도 배치/스케줄러 불필요). 발신자가 밤에 잘못 눌러도 BE 가
 * 자동 연기 = 시간 못 어김. 정책은 docs/features/push.md.
 */
public final class MarketingSendWindow {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPEN = LocalTime.of(8, 0);
    private static final LocalTime CLOSE = LocalTime.of(21, 0);

    private MarketingSendWindow() {
    }

    /**
     * {@code target} 시각이 허용 윈도우(08~21 KST) 밖이면 다음 08:00 KST 로 클램프해서 돌려준다.
     * 반환은 절대 시각을 담은 {@link OffsetDateTime}(KST 오프셋) — outbox {@code nextAttemptAt} 저장/디스패처
     * 비교가 모두 UTC 기준 {@link OffsetDateTime}(instant 비교)이라 오프셋과 무관하게 같은 순간을 가리킨다.
     */
    public static OffsetDateTime clamp(Instant target) {
        ZonedDateTime kst = target.atZone(KST);
        LocalTime t = kst.toLocalTime();
        ZonedDateTime allowed;
        if (t.isBefore(OPEN)) {
            allowed = kst.toLocalDate().atTime(OPEN).atZone(KST);                 // 같은 날 08:00
        } else if (!t.isBefore(CLOSE)) {
            allowed = kst.toLocalDate().plusDays(1).atTime(OPEN).atZone(KST);     // 다음 날 08:00
        } else {
            allowed = kst;                                                        // 허용 시간대 → 그대로
        }
        return allowed.toOffsetDateTime();
    }
}
