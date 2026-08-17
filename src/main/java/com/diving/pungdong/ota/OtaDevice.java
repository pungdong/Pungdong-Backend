package com.diving.pungdong.ota;

import com.diving.pungdong.account.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * OTA 텔레메트리 — 설치 1건의 최신 상태. 부팅마다 upsert 되고, 이벤트는 타입별 마지막 상태만 남긴다.
 *
 * <p><b>왜 {@code firebase_token} 이 아니라 별도 테이블인가</b>: 그 테이블은 "푸시 토큰"의 수명을 산다 —
 * 로그아웃 시 삭제되고, 탈퇴 시 하드삭제되고, 푸시 권한을 거부한 기기는 애초에 행이 없다. 거기에 얹으면
 * "잘못된 번들이 나갔을 때 몇 명이 어디 있나"가 구조적으로 과소집계되는데, 하필 <b>잘못된 번들에 갇힌
 * 사용자는 앱이 이상해서 로그아웃했을 가능성이 높아 가장 보고 싶은 집단이 우선적으로 지워진다.</b>
 * 그래서 앱이 만든 {@code installId} 를 키로 하는 독립 테이블을 둔다(사용자 결정 D1).
 *
 * <p>정책·계약은 {@code docs/features/ota-telemetry.md}, 앱 아키텍처는 PungDong 레포
 * {@code docs/features/ota.md}.
 */
@Entity
@Table(name = "ota_device",
        uniqueConstraints = @UniqueConstraint(name = "uk_ota_device_install_id", columnNames = "install_id"),
        indexes = {
                @Index(name = "idx_ota_device_bundle", columnList = "ota_channel, ota_bundle_id, last_seen_at"),
                @Index(name = "idx_ota_device_downloaded", columnList = "downloaded_bundle_id"),
                @Index(name = "idx_ota_device_srollback", columnList = "server_rollback_from_bundle_id"),
                @Index(name = "idx_ota_device_crollback", columnList = "crash_rollback_bundle_id"),
                @Index(name = "idx_ota_device_seen", columnList = "ota_channel, last_seen_at"),
                @Index(name = "idx_ota_device_account", columnList = "account_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class OtaDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 앱이 최초 실행에 생성해 AsyncStorage 에 영속하는 불투명 설치 식별자. 재설치하면 새 값이다.
     *
     * <p>🔒 <b>암호학적 난수가 아니다</b>(RN 엔 WebCrypto 가 없어 {@code Math.random()} 유래) —
     * <b>인증 수단이 아니며, 이 값을 키로 하는 비인증 <i>읽기</i> 경로를 절대 만들지 말 것.</b>
     * 현재 비인증 경로는 쓰기 전용이고, 읽는 경로는 {@code GET /admin/ota/devices?installId=} 하나뿐이며
     * ADMIN 매처 뒤에 있다. 이 속성이 깨지면 남의 기기 정보를 열거할 수 있게 된다.
     */
    @Column(name = "install_id", nullable = false, length = 64)
    private String installId;

    /** FK 없음(알림 outbox 와 같은 기조). 비로그인 기기는 null, 탈퇴 익명화 시 null 로 끊고 행은 남긴다. */
    @Column(name = "account_id")
    private Long accountId;

    /**
     * 이벤트가 부팅 upsert 보다 먼저 도착해 만들어진 최소 행이면 null(다음 부팅이 채운다).
     * 요청 바디에서는 required 다 — 앱이 {@code Platform.OS} 에서 만드는 JS 상수라 throw 하지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 16)
    private DeviceType platform;

    /** {@code HotUpdater.getAppVersion()} — nullable 이다. NOT NULL 로 잡으면 그 기기가 영영 집계 밖. */
    @Column(name = "app_version", length = 20)
    private String appVersion;

    /** {@code staging} | {@code production}. 문자열이다 — 운영이 채널을 늘려도 역직렬화가 안 깨지게. */
    @Column(name = "ota_channel", length = 16)
    private String otaChannel;

    /** 실측 40자 hex(SHA-1)이지만 알고리즘이 라이브러리 소관이라 형식을 검증하지 않는다. */
    @Column(name = "fingerprint_hash", length = 64)
    private String fingerprintHash;

    /**
     * 현재 실행 중인 OTA 번들. <b>내장 번들이면 null</b>.
     *
     * <p>⚠️ 0.36 JS 의 {@code getBundleId()} 는 OTA 를 한 번도 안 받은 기기에도 NIL 이 아니라
     * {@code getMinBundleId()}(빌드시각 유래 uuidv7)를 돌려준다. 앱이 {@code getBundleId() ===
     * getMinBundleId()} 로 판별해 null 을 보내지만, 그 매핑이 한 번이라도 새면 D1 에 존재하지 않는 uuid 가
     * 쌓여 어드민의 "삭제된 번들에 갇힌 기기" 탐지가 오탐된다. 그래서 서버도
     * {@link #normalizeEmbeddedBundle()} 로 한 번 더 막는다(이중 방어).
     */
    @Column(name = "ota_bundle_id", length = 36)
    private String otaBundleId;

    @Column(name = "ota_min_bundle_id", length = 36)
    private String otaMinBundleId;

    /**
     * {@code "1"}~{@code "1000"} 숫자 문자열 또는 커스텀 슬러그(QA). <b>BE 는 집계·필터에 쓰지 않는다.</b>
     *
     * <p>받는 이유는 어드민만 할 수 있는 두 가지다 — (1) rollout 인하 시 회수 대상 기기 수를 추정이 아니라
     * 정확히 계산, (2) <b>커스텀 코호트라 rollout 대상이 아닌 기기</b> 탐지. 후자는 지금 탐지 수단이 전무하다:
     * 그 기기는 정상 부팅하고 정상으로 체크 요청을 보내고 서버는 정상 200 을 주는데 번들만 안 온다.
     */
    @Column(name = "ota_cohort", length = 64)
    private String otaCohort;

    /** {@code getCrashHistory()} 누적 배열의 JSON 직렬화. 보조 신호 — 정확·즉시는 CRASH_ROLLBACK 이벤트. */
    @Column(name = "crash_history", columnDefinition = "TEXT")
    private String crashHistory;

    @Column(name = "downloaded_bundle_id", length = 36)
    private String downloadedBundleId;

    @Column(name = "downloaded_at")
    private OffsetDateTime downloadedAt;

    @Column(name = "server_rollback_from_bundle_id", length = 36)
    private String serverRollbackFromBundleId;

    @Column(name = "server_rollback_at")
    private OffsetDateTime serverRollbackAt;

    @Column(name = "crash_rollback_bundle_id", length = 36)
    private String crashRollbackBundleId;

    /**
     * ★ <b>크래시 시각이 아니라 보고 시각이다.</b> 크래시는 이전 실행에서 났고 네이티브가 롤백한 뒤
     * 다음 부팅에 {@code onNotifyAppReady} 로 보고하므로, 사용자가 며칠 뒤 앱을 켜면 그만큼 늦게 찍힌다.
     * 실제 크래시 시각은 네이티브가 주지 않아 관측 불가라, 이름으로 그 사실을 드러낸다.
     */
    @Column(name = "crash_rollback_reported_at")
    private OffsetDateTime crashRollbackReportedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 내장 번들 정규화 — {@code otaBundleId} 가 {@code otaMinBundleId} 와 같으면 "OTA 미수신"이므로 null 로 만든다.
     * 앱과 BE 양쪽에서 막는 이중 방어(필드 주석 참고).
     */
    public void normalizeEmbeddedBundle() {
        if (this.otaBundleId != null && this.otaBundleId.equals(this.otaMinBundleId)) {
            this.otaBundleId = null;
        }
    }

    public void touchSeen() {
        this.lastSeenAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 마지막 upsert 이후 {@code seconds} 초가 지났는지 — 쓰기 병합(레이트리밋 1차 방어) 판정. */
    public boolean seenWithin(long seconds, OffsetDateTime now) {
        return this.lastSeenAt != null && this.lastSeenAt.isAfter(now.minusSeconds(seconds));
    }
}
