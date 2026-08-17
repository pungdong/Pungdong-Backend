package com.diving.pungdong.ota;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.ota.dto.OtaBundleStats;
import com.diving.pungdong.ota.dto.OtaBundleStatsResponse;
import com.diving.pungdong.ota.dto.OtaDeviceSummary;
import com.diving.pungdong.ota.dto.OtaSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 어드민 OTA 집계. <b>BE 는 Cloudflare D1 을 읽지 않는다</b> — 번들 메타(메시지·커밋·enabled·rollout·force)는
 * D1 이 유일한 출처이고 어드민이 in-process 로 읽어 {@code bundleId} 키로 합친다. 여기선 <b>기기 카운트만</b> 낸다.
 *
 * <p>라이브러리 D1 스키마가 8개월에 두 번 바뀐 이력이 있어, 컬럼명을 Java 에 새기면 업그레이드가 곧
 * 사일런트 데이터 손상이 된다 — 그래서 경계를 이렇게 그었다.
 */
@Service
@RequiredArgsConstructor
public class OtaAdminService {

    /** 기본 활성 윈도우. 응답에 실어 내려 화면이 "최근 N일 기준"을 그대로 말하게 한다. */
    public static final int DEFAULT_ACTIVE_WINDOW_DAYS = 7;

    /** {@code bundleIds} 한 번에 조회 가능한 상한. 어드민 페이지 크기(50)의 두 배. */
    public static final int MAX_BUNDLE_IDS = 100;

    /** 분포 배열 상위 N — 나머지는 key=null 행으로 합산한다. */
    private static final int DISTRIBUTION_TOP_N = 20;

    private static final Sort DEVICE_SORT =
            Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id"));

    private final OtaDeviceJpaRepo deviceRepo;
    private final AccountJpaRepo accountRepo;
    private final ObjectMapper objectMapper;

    /* ─── §2.1 bundle-stats ───────────────────────────────────────────────── */

    /**
     * 번들별 카운트.
     *
     * @param bundleIds 지정하면 <b>그 id 만, 요청 순서 그대로, 없는 id 도 전부 0</b>. null/빈 값이면
     *                  BE 가 아는 <b>전량</b>을 {@code bundleId DESC}(uuidv7 이라 시간 역순)로.
     *                  전량 모드가 필요한 이유는 <b>D1 에 없는 고아 번들</b>(삭제됐는데 기기는 아직 실행 중)을
     *                  어드민이 찾아야 하기 때문이다 — 지정 모드만 있으면 그런 번들은 질문 목록에 못 들어간다.
     */
    @Transactional(readOnly = true)
    public OtaBundleStatsResponse bundleStats(List<String> bundleIds, String channel,
                                              DeviceType platform, int activeWindowDays) {
        if (bundleIds != null && bundleIds.size() > MAX_BUNDLE_IDS) {
            throw new BadRequestException("한 번에 " + MAX_BUNDLE_IDS + "개까지 조회할 수 있어요.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime since = now.minusDays(activeWindowDays);

        // ⚠️ 지정 모드에서도 전량을 집계한 뒤 서비스가 좁힌다 — 모드마다 다른 쿼리를 쓰면 두 화면의 수가 갈린다.
        Map<String, Long> active = toMap(deviceRepo.tallyActive(since, channel, platform));
        Map<String, Long> installed = toMap(deviceRepo.tallyInstalled(channel, platform));
        Map<String, Long> downloaded = toMap(deviceRepo.tallyDownloaded(channel, platform));
        Map<String, Long> downloadedNotInstalled = toMap(deviceRepo.tallyDownloadedNotInstalled(channel, platform));
        Map<String, Long> serverRolledBack = toMap(deviceRepo.tallyServerRolledBack(channel, platform));
        Map<String, Long> crashRolledBack = tallyCrashRolledBack(channel, platform);

        List<String> targets;
        if (bundleIds != null && !bundleIds.isEmpty()) {
            // 요청 순서 유지 + 중복 제거. 없는 id 도 엔트리를 만든다(zero-fill) — 누락은 상태가 아니라 버그다.
            targets = new ArrayList<>(new LinkedHashSet<>(bundleIds));
        } else {
            Set<String> all = new java.util.TreeSet<>(Comparator.reverseOrder());
            all.addAll(active.keySet());
            all.addAll(installed.keySet());
            all.addAll(downloaded.keySet());
            all.addAll(downloadedNotInstalled.keySet());
            all.addAll(serverRolledBack.keySet());
            all.addAll(crashRolledBack.keySet());
            targets = new ArrayList<>(all);
        }

        List<OtaBundleStats> stats = targets.stream()
                .map(id -> OtaBundleStats.builder()
                        .bundleId(id)
                        .active(active.getOrDefault(id, 0L))
                        .installed(installed.getOrDefault(id, 0L))
                        .downloaded(downloaded.getOrDefault(id, 0L))
                        .downloadedNotInstalled(downloadedNotInstalled.getOrDefault(id, 0L))
                        .serverRolledBack(serverRolledBack.getOrDefault(id, 0L))
                        .crashRolledBack(crashRolledBack.getOrDefault(id, 0L))
                        .build())
                .collect(Collectors.toList());

        return OtaBundleStatsResponse.builder()
                .activeWindowDays(activeWindowDays)
                .generatedAt(now)
                .stats(stats)
                .build();
    }

    /* ─── §2.2 번들별 기기 목록 ───────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Page<OtaDeviceSummary> devicesOfBundle(String bundleId, OtaDeviceState state,
                                                  int activeWindowDays, Pageable pageable) {
        Pageable sorted = withFixedSort(pageable);
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(activeWindowDays);
        String crashPattern = OtaCrashHistory.likePattern(bundleId);

        Page<OtaDevice> page = switch (state) {
            case ALL -> deviceRepo.findAllRelatedToBundle(bundleId, crashPattern, sorted);
            case ACTIVE -> deviceRepo.findActiveByBundle(bundleId, since, sorted);
            case INSTALLED -> deviceRepo.findByOtaBundleId(bundleId, sorted);
            case DOWNLOADED -> deviceRepo.findByDownloadedBundleId(bundleId, sorted);
            case DOWNLOADED_NOT_INSTALLED -> deviceRepo.findDownloadedNotInstalled(bundleId, sorted);
            case SERVER_ROLLED_BACK -> deviceRepo.findByServerRollbackFromBundleId(bundleId, sorted);
            case CRASH_ROLLED_BACK -> deviceRepo.findCrashRolledBack(bundleId, crashPattern, sorted);
        };
        return toSummaryPage(page);
    }

    /* ─── §2.3 유저 드릴다운 ──────────────────────────────────────────────── */

    /**
     * ⚠️ 레포 규칙 "신분은 세션에서, {@code userId} 파라미터는 red flag" 의 <b>정당한 예외</b>다 —
     * 여기서 {@code userId} 는 요청자의 신분이 아니라 <b>조회 대상</b>이고, 요청자 신분은
     * {@code hasRole("ADMIN")} 매처가 검증한다(기존 {@code /admin/instructor-applications/{id}} 와 같은 성격).
     */
    @Transactional(readOnly = true)
    public Page<OtaDeviceSummary> devicesByUserOrInstall(Long userId, String installId, Pageable pageable) {
        boolean hasUser = userId != null;
        boolean hasInstall = installId != null && !installId.isBlank();
        if (hasUser == hasInstall) {
            throw new BadRequestException("userId 또는 installId 중 하나만 지정해주세요.");
        }
        Pageable sorted = withFixedSort(pageable);
        Page<OtaDevice> page = hasUser
                ? deviceRepo.findByAccountId(userId, sorted)
                : deviceRepo.findByInstallId(installId, sorted);
        return toSummaryPage(page);
    }

    /* ─── §2.4 summary ────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public OtaSummary summary(String channel, int activeWindowDays) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime since = now.minusDays(activeWindowDays);

        long activeDevices = deviceRepo.countActiveDevices(since, channel);
        long embeddedDevices = deviceRepo.countEmbeddedDevices(since, channel);
        long linkedDevices = deviceRepo.countLinkedDevices(since, channel);

        List<Bucket> appVersions = topWithTail(deviceRepo.tallyByAppVersion(since, channel));
        List<Bucket> fingerprints = topWithTail(deviceRepo.tallyByFingerprint(since, channel));

        return OtaSummary.builder()
                .channel(channel)
                .activeWindowDays(activeWindowDays)
                .generatedAt(now)
                .activeDevices(activeDevices)
                .embeddedDevices(embeddedDevices)
                .linkedDevices(linkedDevices)
                .byAppVersion(appVersions.stream()
                        .map(b -> OtaSummary.AppVersionBucket.builder()
                                .appVersion(b.key).count(b.count).build())
                        .collect(Collectors.toList()))
                .byFingerprint(fingerprints.stream()
                        .map(b -> OtaSummary.FingerprintBucket.builder()
                                .fingerprintHash(b.key).count(b.count).build())
                        .collect(Collectors.toList()))
                .build();
    }

    /* ─── 내부 ──────────────────────────────────────────────────────────── */

    /**
     * 정렬은 <b>고정</b>한다 — {@code last_seen_at DESC, id DESC}. 클라이언트 정렬을 받지 않는 이유는
     * 동률 타이브레이커가 없으면 페이지네이션이 행을 건너뛰거나 중복시키기 때문이다.
     */
    private static Pageable withFixedSort(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEVICE_SORT);
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    /**
     * {@code crashRolledBack} 은 "컬럼 일치 <b>OR</b> crash_history 배열에 포함" 이라 한 번의 GROUP BY 로
     * 못 센다. 후보(크래시 롤백을 실제로 겪은 기기)만 뽑아 여기서 집계한다 — 빈 배열은 저장 시 null 로
     * 정규화되므로 후보 수는 매우 적다.
     *
     * <p>목록 조회(§2.2)는 SQL {@code LIKE} 로 같은 술어를 평가한다 — 두 표현이 갈리면 어드민에서 숫자를
     * 눌렀을 때 다른 수가 나오므로, 양쪽 다 {@link OtaCrashHistory} 를 통과시킨다.
     */
    private Map<String, Long> tallyCrashRolledBack(String channel, DeviceType platform) {
        List<Object[]> candidates = deviceRepo.crashRollbackCandidates(channel, platform);
        Map<String, Long> tally = new HashMap<>();
        for (Object[] row : candidates) {
            String columnBundleId = (String) row[0];
            String history = (String) row[1];
            Set<String> counted = new LinkedHashSet<>();
            if (columnBundleId != null) {
                counted.add(columnBundleId);
            }
            for (String fromHistory : OtaCrashHistory.toList(objectMapper, history)) {
                counted.add(fromHistory);
            }
            // 같은 기기가 같은 번들로 두 번 세이지 않게 Set 으로 접은 뒤 1씩 더한다.
            for (String bundleId : counted) {
                tally.merge(bundleId, 1L, Long::sum);
            }
        }
        return tally;
    }

    private Page<OtaDeviceSummary> toSummaryPage(Page<OtaDevice> page) {
        List<Long> accountIds = page.getContent().stream()
                .map(OtaDevice::getAccountId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> nickNames = accountIds.isEmpty()
                ? Map.of()
                : accountRepo.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getNickName, (a, b) -> a));

        return page.map(device -> toSummary(device, nickNames));
    }

    private OtaDeviceSummary toSummary(OtaDevice device, Map<Long, String> nickNames) {
        OtaDeviceSummary.OtaDeviceUser user = null;
        if (device.getAccountId() != null && nickNames.containsKey(device.getAccountId())) {
            user = OtaDeviceSummary.OtaDeviceUser.builder()
                    .id(device.getAccountId())
                    .nickName(nickNames.get(device.getAccountId()))
                    .build();
        }
        return OtaDeviceSummary.builder()
                .installId(device.getInstallId())
                .platform(device.getPlatform())
                .appVersion(device.getAppVersion())
                .otaChannel(device.getOtaChannel())
                .otaBundleId(device.getOtaBundleId())
                .otaMinBundleId(device.getOtaMinBundleId())
                .fingerprintHash(device.getFingerprintHash())
                .otaCohort(device.getOtaCohort())
                .downloadedBundleId(device.getDownloadedBundleId())
                .downloadedAt(device.getDownloadedAt())
                .serverRollbackFromBundleId(device.getServerRollbackFromBundleId())
                .serverRollbackAt(device.getServerRollbackAt())
                .crashRollbackBundleId(device.getCrashRollbackBundleId())
                .crashRollbackReportedAt(device.getCrashRollbackReportedAt())
                .crashHistory(OtaCrashHistory.toList(objectMapper, device.getCrashHistory()))
                .lastSeenAt(device.getLastSeenAt())
                .user(user)
                .build();
    }

    /**
     * {@code count DESC} 상위 N + 나머지 합산 행(key=null). 값이 없어서 null 인 버킷과 꼬리를 <b>한 행으로
     * 합친다</b> — 어드민이 "기타/미상" 하나로 렌더하고, 총합은 항상 {@code activeDevices} 와 맞는다.
     */
    private static List<Bucket> topWithTail(List<Object[]> rows) {
        List<Bucket> named = new ArrayList<>();
        long unknown = 0L;
        for (Object[] row : rows) {
            long count = ((Number) row[1]).longValue();
            if (row[0] == null) {
                unknown += count;
            } else {
                named.add(new Bucket((String) row[0], count));
            }
        }
        named.sort(Comparator.comparingLong((Bucket b) -> b.count).reversed()
                .thenComparing(b -> b.key));

        List<Bucket> result = new ArrayList<>();
        for (int i = 0; i < named.size(); i++) {
            if (i < DISTRIBUTION_TOP_N) {
                result.add(named.get(i));
            } else {
                unknown += named.get(i).count;
            }
        }
        if (unknown > 0) {
            result.add(new Bucket(null, unknown));
        }
        return result;
    }

    private static final class Bucket {
        private final String key;
        private final long count;

        private Bucket(String key, long count) {
            this.key = key;
            this.count = count;
        }
    }
}
