package com.diving.pungdong.ota;

import com.diving.pungdong.account.DeviceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * OTA 기기 조회/집계.
 *
 * <p><b>집계 전략</b>: 지표별 {@code GROUP BY} 한 번씩 돌려 {@code [bundleId, count]} 행을 받고 서비스가
 * Map 으로 접는다(레포 하우스 스타일 — JPQL constructor expression 은 이 레포에 0건). 결과 집합의 크기는
 * <b>구분되는 번들 수</b>라 작다(배포 1회 = 번들 1~2개).
 *
 * <p>⚠️ <b>bundleIds 로 좁히지 않고 항상 전량을 집계한다.</b> 그래야 `bundleIds` 지정 모드와 전량 모드가
 * <b>같은 숫자</b>를 낸다 — 모드마다 다른 쿼리를 쓰면 두 화면의 수가 조용히 갈린다. 좁히기는 서비스가 한다.
 */
public interface OtaDeviceJpaRepo extends JpaRepository<OtaDevice, Long> {

    Optional<OtaDevice> findByInstallId(String installId);

    /* ─── 집계 (§2.1 bundle-stats) ────────────────────────────────────────── */

    /** 실행 중 + 윈도우 안. "지금 실행 중"은 관측 불가라 "최근 N일 안에 봤다"는 뜻이다. */
    @Query("select d.otaBundleId, count(d) from OtaDevice d "
            + "where d.otaBundleId is not null and d.lastSeenAt >= :since "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform) "
            + "group by d.otaBundleId")
    List<Object[]> tallyActive(@Param("since") OffsetDateTime since,
                               @Param("channel") String channel,
                               @Param("platform") DeviceType platform);

    /** 마지막 보고 시점에 그 번들을 실행 중이던 기기(윈도우 무관). ★ 누적이 아니다. */
    @Query("select d.otaBundleId, count(d) from OtaDevice d "
            + "where d.otaBundleId is not null "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform) "
            + "group by d.otaBundleId")
    List<Object[]> tallyInstalled(@Param("channel") String channel,
                                  @Param("platform") DeviceType platform);

    /** 받은 기기 전부 — 설치까지 끝낸 기기 포함(총 배포 도달량). */
    @Query("select d.downloadedBundleId, count(d) from OtaDevice d "
            + "where d.downloadedBundleId is not null "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform) "
            + "group by d.downloadedBundleId")
    List<Object[]> tallyDownloaded(@Param("channel") String channel,
                                   @Param("platform") DeviceType platform);

    /** "받았는데 아직 안 켠" — 에픽이 지목한 핵심 지표. */
    @Query("select d.downloadedBundleId, count(d) from OtaDevice d "
            + "where d.downloadedBundleId is not null "
            + "and (d.otaBundleId is null or d.otaBundleId <> d.downloadedBundleId) "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform) "
            + "group by d.downloadedBundleId")
    List<Object[]> tallyDownloadedNotInstalled(@Param("channel") String channel,
                                               @Param("platform") DeviceType platform);

    /** 어드민 disable/rollout 인하로 정상 복귀 — 알람 대상이 아니다(무채색 표기). */
    @Query("select d.serverRollbackFromBundleId, count(d) from OtaDevice d "
            + "where d.serverRollbackFromBundleId is not null "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform) "
            + "group by d.serverRollbackFromBundleId")
    List<Object[]> tallyServerRolledBack(@Param("channel") String channel,
                                         @Param("platform") DeviceType platform);

    /**
     * 크래시 롤백 후보 행 — {@code [crashRollbackBundleId, crashHistory]}.
     *
     * <p>{@code crashRolledBack} 은 "컬럼 일치 <b>OR</b> crash_history 배열에 포함" 이라 한 번의
     * {@code GROUP BY} 로 못 센다(배열 하나가 여러 번들을 가리킨다). 대신 <b>후보만</b> 뽑아 서비스가 집계한다 —
     * 후보 = 크래시 롤백을 실제로 겪은 기기뿐이라 매우 적다(빈 배열은 저장 시 null 로 정규화된다).
     */
    @Query("select d.crashRollbackBundleId, d.crashHistory from OtaDevice d "
            + "where (d.crashRollbackBundleId is not null or d.crashHistory is not null) "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "and (:platform is null or d.platform = :platform)")
    List<Object[]> crashRollbackCandidates(@Param("channel") String channel,
                                           @Param("platform") DeviceType platform);

    /* ─── 요약 (§2.4 summary) ─────────────────────────────────────────────── */

    @Query("select count(d) from OtaDevice d where d.lastSeenAt >= :since "
            + "and (:channel is null or d.otaChannel = :channel)")
    long countActiveDevices(@Param("since") OffsetDateTime since, @Param("channel") String channel);

    /** OTA 미수신(스토어 버전 그대로) 기기 수. */
    @Query("select count(d) from OtaDevice d where d.lastSeenAt >= :since and d.otaBundleId is null "
            + "and (:channel is null or d.otaChannel = :channel)")
    long countEmbeddedDevices(@Param("since") OffsetDateTime since, @Param("channel") String channel);

    /** account 링크된 기기 수 = 유저 드릴다운 가능 비율(모수 신뢰도 지표). */
    @Query("select count(d) from OtaDevice d where d.lastSeenAt >= :since and d.accountId is not null "
            + "and (:channel is null or d.otaChannel = :channel)")
    long countLinkedDevices(@Param("since") OffsetDateTime since, @Param("channel") String channel);

    @Query("select d.appVersion, count(d) from OtaDevice d where d.lastSeenAt >= :since "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "group by d.appVersion")
    List<Object[]> tallyByAppVersion(@Param("since") OffsetDateTime since, @Param("channel") String channel);

    @Query("select d.fingerprintHash, count(d) from OtaDevice d where d.lastSeenAt >= :since "
            + "and (:channel is null or d.otaChannel = :channel) "
            + "group by d.fingerprintHash")
    List<Object[]> tallyByFingerprint(@Param("since") OffsetDateTime since, @Param("channel") String channel);

    /* ─── 기기 목록 (§2.2 / §2.3) ─────────────────────────────────────────── */
    /* 정렬은 호출부가 Pageable 로 고정한다(last_seen_at DESC, id DESC — 동률 타이브레이커 필수). */

    @Query("select d from OtaDevice d where d.otaBundleId = :bundleId "
            + "or d.downloadedBundleId = :bundleId "
            + "or d.serverRollbackFromBundleId = :bundleId "
            + "or d.crashRollbackBundleId = :bundleId "
            + "or d.crashHistory like :crashPattern")
    Page<OtaDevice> findAllRelatedToBundle(@Param("bundleId") String bundleId,
                                           @Param("crashPattern") String crashPattern,
                                           Pageable pageable);

    @Query("select d from OtaDevice d where d.otaBundleId = :bundleId and d.lastSeenAt >= :since")
    Page<OtaDevice> findActiveByBundle(@Param("bundleId") String bundleId,
                                       @Param("since") OffsetDateTime since,
                                       Pageable pageable);

    Page<OtaDevice> findByOtaBundleId(String bundleId, Pageable pageable);

    Page<OtaDevice> findByDownloadedBundleId(String bundleId, Pageable pageable);

    @Query("select d from OtaDevice d where d.downloadedBundleId = :bundleId "
            + "and (d.otaBundleId is null or d.otaBundleId <> d.downloadedBundleId)")
    Page<OtaDevice> findDownloadedNotInstalled(@Param("bundleId") String bundleId, Pageable pageable);

    Page<OtaDevice> findByServerRollbackFromBundleId(String bundleId, Pageable pageable);

    /** {@code crashRolledBack} 카운트와 <b>같은 술어</b>여야 한다 — 술어가 갈리면 숫자와 목록이 어긋난다. */
    @Query("select d from OtaDevice d where d.crashRollbackBundleId = :bundleId "
            + "or d.crashHistory like :crashPattern")
    Page<OtaDevice> findCrashRolledBack(@Param("bundleId") String bundleId,
                                        @Param("crashPattern") String crashPattern,
                                        Pageable pageable);

    Page<OtaDevice> findByAccountId(Long accountId, Pageable pageable);

    Page<OtaDevice> findByInstallId(String installId, Pageable pageable);

    /* ─── 탈퇴 익명화 ─────────────────────────────────────────────────────── */

    /**
     * 계정 링크만 끊는다 — <b>행은 남긴다.</b> 기기 통계는 PII 가 아니고, 하드삭제하면 그 기기가 어느 번들에
     * 있는지가 사라져 릴리스 대시보드에 구멍이 난다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OtaDevice d set d.accountId = null where d.accountId = :accountId")
    int unlinkAccount(@Param("accountId") Long accountId);
}
