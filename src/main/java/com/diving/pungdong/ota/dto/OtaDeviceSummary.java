package com.diving.pungdong.ota.dto;

import com.diving.pungdong.account.DeviceType;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 어드민 기기 목록의 한 행. {@code GET /admin/ota/bundles/{id}/devices} 와
 * {@code GET /admin/ota/devices} 가 <b>같은 DTO</b>를 쓴다(배열 rel = {@code otaDevices}).
 *
 * <p>PII 는 싣지 않는다 — {@code nickName} 은 공개 표시 핸들이라 PII 가 아니지만 이메일·전화번호는 없다.
 */
@Getter
@Builder
@Relation(collectionRelation = "otaDevices")
public class OtaDeviceSummary {

    private final String installId;

    /** 이벤트가 부팅 upsert 보다 먼저 도착해 만들어진 최소 행이면 null. */
    private final DeviceType platform;

    private final String appVersion;
    private final String otaChannel;

    /** null = 내장 번들(OTA 미수신). */
    private final String otaBundleId;

    private final String otaMinBundleId;
    private final String fingerprintHash;

    /**
     * {@code "1"}~{@code "1000"} 또는 커스텀 슬러그. 커스텀이면 그 기기는 번들의 {@code targetCohorts} 에
     * 명시되지 않는 한 <b>rollout 100% 여도 번들을 못 받는다</b> — 어드민이 이 행을 뽑아내는 용도.
     */
    private final String otaCohort;

    private final String downloadedBundleId;
    private final OffsetDateTime downloadedAt;
    private final String serverRollbackFromBundleId;
    private final OffsetDateTime serverRollbackAt;
    private final String crashRollbackBundleId;

    /**
     * ★ <b>크래시 시각이 아니라 보고 시각</b>이다. 이 값으로 크래시 타임라인을 그리면 "배포 3일 뒤 크래시"처럼
     * 보이는데 실제론 그때 앱을 켠 것뿐이다.
     */
    private final OffsetDateTime crashRollbackReportedAt;

    /** 없으면 빈 배열(null 아님) — FE 가 null 체크를 안 하게. */
    private final List<String> crashHistory;

    private final OffsetDateTime lastSeenAt;

    /** 비로그인/탈퇴 기기면 null — B안이라 이게 <b>정상</b>이다. */
    private final OtaDeviceUser user;

    @Getter
    @Builder
    public static class OtaDeviceUser {
        private final Long id;
        private final String nickName;
    }
}
