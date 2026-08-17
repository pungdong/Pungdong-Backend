package com.diving.pungdong.ota.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /admin/ota/summary} — 대시보드 KPI + 분포.
 *
 * <p>분포 바는 어드민이 직접 그린다(차트 라이브러리 없음) — {@code count DESC} 정렬에
 * {@code activeDevices} 를 분모로 쓴다.
 *
 * <p><b>{@code byBundle} 이 여기 없는 이유</b>: {@code GET /admin/ota/bundle-stats} 의 전량 모드가 정확히
 * 그 역할을 한다. 같은 숫자를 두 엔드포인트에서 정의하면 조용히 드리프트한다.
 */
@Getter
@Builder
public class OtaSummary {

    /** 생략 시 null = 전 채널. */
    private final String channel;

    private final int activeWindowDays;
    private final OffsetDateTime generatedAt;

    /**
     * 분포 바의 분모.
     * ★ <b>"활성 설치 수"이지 "실물 대수"가 아니다</b> — installId 는 재설치 시 재발급이라 같은 물리 기기가
     * 여러 행으로 잡혔다가 윈도우로 자연 소멸한다(B안의 성질, 결함 아님).
     */
    private final long activeDevices;

    /** 그중 OTA 미수신(스토어 버전 그대로). */
    private final long embeddedDevices;

    /** 그중 account 링크됨 = 유저 드릴다운 가능 비율(모수 신뢰도 지표). */
    private final long linkedDevices;

    /** {@code count DESC}, 상위 20 + 나머지는 {@code key=null} 행으로 합산. 총합 = {@code activeDevices}. */
    private final List<AppVersionBucket> byAppVersion;

    private final List<FingerprintBucket> byFingerprint;

    @Getter
    @Builder
    public static class AppVersionBucket {
        /** null = 미보고 + 상위 20 밖 꼬리 합산("기타/미상"). */
        private final String appVersion;
        private final long count;
    }

    @Getter
    @Builder
    public static class FingerprintBucket {
        /** null = 미보고 + 상위 20 밖 꼬리 합산("기타/미상"). */
        private final String fingerprintHash;
        private final long count;
    }
}
