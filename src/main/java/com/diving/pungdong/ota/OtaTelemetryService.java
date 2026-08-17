package com.diving.pungdong.ota;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.TooManyRequestsException;
import com.diving.pungdong.ota.dto.OtaDeviceUpsertRequest;
import com.diving.pungdong.ota.dto.OtaEventRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * OTA 텔레메트리 수집 — 부팅 upsert + 이벤트.
 *
 * <p>정책·계약은 {@code docs/features/ota-telemetry.md}. 이 서비스의 모든 결정은 하나의 원칙을 따른다:
 * <b>앱은 4xx/5xx 를 전부 삼키고 재시도하지 않는다</b>. 그래서 거절은 곧 "그 기기가 영구히 집계 밖" 이고,
 * 그 손실은 아무 신호도 남기지 않는다. 애매하면 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtaTelemetryService {

    /** 같은 설치의 부팅 upsert 병합 창 — 정상 재부팅/포그라운드 복귀를 벌하지 않으려고 429 가 아니라 no-op 200. */
    static final long UPSERT_MERGE_SECONDS = 60;

    /** 같은 설치·같은 타입 이벤트 병합 창. */
    static final long EVENT_MERGE_SECONDS = 10;

    /** Redis 키 접두 — 테스트가 정리할 때도 쓴다. */
    public static final String IP_QUOTA_KEY_PREFIX = "ota:reg:ip:";

    private final OtaDeviceJpaRepo deviceRepo;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * IP 당 1시간 신규 설치 생성 상한. 테스트는 <b>기본 off</b> 다 —
     * 임베디드 Redis 를 테스트 클래스들이 공유해서 켜두면 카운터가 누적돼 실행 순서에 따라 결과가 갈린다
     * (application-test.yml 에서 끈다). 상한 자체의 동작은 전용 테스트가 작은 값으로 켜서 검증한다.
     */
    @Value("${pungdong.ota.new-install-ip-quota-enabled:true}")
    private boolean ipQuotaEnabled;

    @Value("${pungdong.ota.new-install-ip-quota:100}")
    private long newInstallPerIpPerHour;

    /**
     * 부팅 upsert. 인증은 선택 — {@code account} 가 null 이면 비로그인 기기다.
     *
     * @param clientIp 신규 행 생성 상한용. 판정 실패 시 {@link Optional#empty()} → 상한을 건너뛴다(fail-open).
     */
    @Transactional
    public void upsert(OtaDeviceUpsertRequest request, Account account, Optional<String> clientIp) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<OtaDevice> existing = deviceRepo.findByInstallId(request.getInstallId());

        if (existing.isPresent()) {
            OtaDevice device = existing.get();
            // 쓰기 병합 — 정상 동작이므로 200 을 준다(429 아님).
            if (device.seenWithin(UPSERT_MERGE_SECONDS, now)) {
                return;
            }
            applyUpsert(device, request, account, now);
            return;
        }

        requireUnderNewInstallQuota(clientIp);
        try {
            OtaDevice created = OtaDevice.builder()
                    .installId(request.getInstallId())
                    .createdAt(now)
                    .lastSeenAt(now)
                    .build();
            applyUpsert(created, request, account, now);
            deviceRepo.save(created);
        } catch (DataIntegrityViolationException e) {
            // install_id UNIQUE 경합 — 동시 첫 부팅. 500 을 내지 않고 재조회 후 갱신으로 수렴시킨다.
            OtaDevice raced = deviceRepo.findByInstallId(request.getInstallId()).orElseThrow(() -> e);
            applyUpsert(raced, request, account, now);
        }
    }

    /**
     * 이벤트 기록. <b>행이 없어도 200</b> — 최소 행을 만든다.
     *
     * <p>왜: {@code withOta} 는 HOC(부모)이고 {@code App} 이 자식이라 effect 는 자식→부모 순으로 돌지만
     * 두 호출 다 비동기 네트워크라 <b>완료 순서가 보장되지 않는다</b>. 특히 {@code onNotifyAppReady} 는
     * 첫 렌더 커밋 직후 즉시 발화한다. 앱이 upsert 를 await 해 순서를 강제할 수도 있지만 그러면 오프라인·
     * 느린 네트워크에서 이벤트가 통째로 막히므로, 서버가 관대한 쪽이 옳다.
     */
    @Transactional
    public void recordEvent(String installId, OtaEventRequest request, Optional<String> clientIp) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OtaDevice device = deviceRepo.findByInstallId(installId).orElse(null);

        if (device == null) {
            requireUnderNewInstallQuota(clientIp);
            device = OtaDevice.builder()
                    .installId(installId)
                    .createdAt(now)
                    .lastSeenAt(now)
                    .build();
            try {
                device = deviceRepo.save(device);
            } catch (DataIntegrityViolationException e) {
                device = deviceRepo.findByInstallId(installId).orElseThrow(() -> e);
            }
        } else if (eventMergedWithin(device, request.getType(), now)) {
            return; // 타입별 병합 창 안 — 정상 200
        }

        String bundleId = request.getBundleId();
        switch (request.getType()) {
            case DOWNLOADED -> {
                device.setDownloadedBundleId(bundleId);
                device.setDownloadedAt(now);
            }
            case SERVER_ROLLBACK -> {
                device.setServerRollbackFromBundleId(bundleId);
                device.setServerRollbackAt(now);
            }
            case CRASH_ROLLBACK -> {
                device.setCrashRollbackBundleId(bundleId);
                // ★ 크래시 시각이 아니라 '보고 시각' — 크래시는 이전 실행에서 났고 관측 불가다.
                device.setCrashRollbackReportedAt(now);
                // 같은 롤백을 부팅 upsert 가 이미 서버 롤백으로 파생해 뒀다면 원인은 사고였으므로 되돌린다.
                clearServerRollbackIfCrash(device, bundleId);
            }
            default -> throw new IllegalStateException("unhandled event type: " + request.getType());
        }
    }

    /* ─── 내부 ──────────────────────────────────────────────────────────── */

    private void applyUpsert(OtaDevice device, OtaDeviceUpsertRequest request, Account account, OffsetDateTime now) {
        // 서버 롤백 파생을 위해 "직전에 실행 중이던 번들"을 덮어쓰기 전에 붙든다.
        String previousBundleId = device.getOtaBundleId();

        // 생략된 필드는 기존 값을 유지한다 — 앱이 필드별 try/catch 로 하나를 빠뜨렸을 때 직전에 잘 보고된
        // 값을 null 로 지우면, 그 기기의 분포가 조용히 "미상"으로 옮겨간다.
        if (request.getPlatform() != null) {
            device.setPlatform(request.getPlatform());
        }
        if (request.getAppVersion() != null) {
            device.setAppVersion(request.getAppVersion());
        }
        if (request.getOtaChannel() != null) {
            device.setOtaChannel(request.getOtaChannel());
        }
        if (request.getFingerprintHash() != null) {
            device.setFingerprintHash(request.getFingerprintHash());
        }
        if (request.getOtaBundleId() != null) {
            device.setOtaBundleId(request.getOtaBundleId());
        }
        if (request.getOtaMinBundleId() != null) {
            device.setOtaMinBundleId(request.getOtaMinBundleId());
        }
        if (request.getOtaCohort() != null) {
            device.setOtaCohort(request.getOtaCohort());
        }
        if (request.getOtaCrashHistory() != null) {
            device.setCrashHistory(OtaCrashHistory.toJson(objectMapper, request.getOtaCrashHistory()));
        }

        // 내장 번들 이중 방어 — 앱의 판별 매핑이 한 번이라도 새면 D1 에 없는 uuid 가 쌓여
        // 어드민의 "삭제된 번들에 갇힌 기기" 탐지가 오탐된다.
        device.normalizeEmbeddedBundle();

        deriveServerRollback(device, previousBundleId, now);

        // 로그인 상태면 링크한다. 비로그인 요청은 기존 링크를 지우지 않는다 —
        // 로그아웃해도 "누구 기기였는지"가 남아야 CS 드릴다운이 끊기지 않는다.
        if (account != null) {
            device.setAccountId(account.getId());
        }
        device.setLastSeenAt(now);
    }

    /**
     * <b>서버 롤백을 부팅 보고에서 파생한다</b> — 앱 이벤트는 보조다.
     *
     * <p><b>왜 앱 이벤트를 못 믿나</b>(mobile 실측): 모든 ROLLBACK 응답은 {@code shouldForceUpdate} 로
     * 덮어써지고, 강제 경로는 {@code await reload()} <b>뒤에</b> {@code onUpdateProcessCompleted} 를 부른다.
     * 리로드되면 그 줄은 실행되지 않으므로 <b>부팅 경로의 SERVER_ROLLBACK 이벤트는 사실상 도달 불가</b>다 —
     * 그런데 "disable 직후 앱을 새로 켠 사용자"가 가장 흔한 경로다. 계약대로 앱만 믿으면 이 카운트는
     * 0 근처에 머물고, 그게 하필 <b>에픽 완료 기준이 보는 숫자</b>다.
     *
     * <p><b>파생 규칙</b>: 번들 id 는 uuidv7 이라 <b>문자열 비교 = 시간순</b>이다. 직전 번들이 있었는데
     * 새 값이 <b>더 작거나(옛 번들) null(내장 복귀)</b> 이면 뒤로 간 것 = 롤백이다.
     * 이 판정은 클라이언트 이벤트 유실과 무관하고, FROM(빠져나온 번들)이 정의상 정확하다.
     *
     * <p><b>크래시 롤백과의 이중 계산 방지 — 양방향으로 막는다.</b> 같은 부팅에서 네이티브 자동 롤백이
     * 일어나면 그것도 "번들이 뒤로 간" 것으로 보이는데, 원인이 정반대라 서버 롤백으로 세면 안 된다.
     * 부팅 upsert 와 {@code CRASH_ROLLBACK} 이벤트의 <b>도착 순서가 보장되지 않으므로</b>(이벤트 엔드포인트가
     * 관대한 것과 같은 이유) 한쪽 가드만으로는 못 막는다:
     * <ul>
     *   <li>이벤트가 <b>먼저</b> 온 경우 → 여기서 {@code crashRollbackBundleId} 를 보고 기록하지 않는다.</li>
     *   <li>upsert 가 <b>먼저</b>인 경우 → 나중에 도착한 {@code CRASH_ROLLBACK} 이
     *       {@link #clearServerRollbackIfCrash} 로 서버 기록을 지운다.</li>
     * </ul>
     */
    private static void deriveServerRollback(OtaDevice device, String previousBundleId, OffsetDateTime now) {
        if (previousBundleId == null) {
            return; // 신규 행이거나 원래 내장 — 빠져나온 번들이 없다
        }
        String current = device.getOtaBundleId();
        boolean wentBackwards = (current == null) || current.compareTo(previousBundleId) < 0;
        if (!wentBackwards) {
            return;
        }
        // 같은 번들에 대한 크래시 롤백이 이미 보고됐으면 그쪽이 원인이다(사고 ≠ 운영 조작).
        if (previousBundleId.equals(device.getCrashRollbackBundleId())) {
            return;
        }
        device.setServerRollbackFromBundleId(previousBundleId);
        device.setServerRollbackAt(now);
    }

    /**
     * upsert 가 먼저 와서 서버 롤백으로 기록해 뒀는데 같은 번들의 {@code CRASH_ROLLBACK} 이 뒤늦게 도착하면,
     * 원인은 사고였으므로 서버 롤백 기록을 <b>지운다</b>. 안 지우면 한 번의 롤백이 두 카운트에 동시에 잡혀
     * "disable 했더니 크래시도 났다" 로 읽힌다.
     */
    private static void clearServerRollbackIfCrash(OtaDevice device, String bundleId) {
        if (bundleId.equals(device.getServerRollbackFromBundleId())) {
            device.setServerRollbackFromBundleId(null);
            device.setServerRollbackAt(null);
        }
    }

    private boolean eventMergedWithin(OtaDevice device, OtaEventType type, OffsetDateTime now) {
        OffsetDateTime last = switch (type) {
            case DOWNLOADED -> device.getDownloadedAt();
            case SERVER_ROLLBACK -> device.getServerRollbackAt();
            case CRASH_ROLLBACK -> device.getCrashRollbackReportedAt();
        };
        return last != null && last.isAfter(now.minusSeconds(EVENT_MERGE_SECONDS));
    }

    /**
     * 신규 설치 행 생성 상한(IP 단위). <b>fail-open</b> — IP 판정 실패나 Redis 장애면 그냥 통과시킨다.
     * 텔레메트리를 조금 더 받는 것보다 정상 기기를 막는 쪽이 훨씬 나쁘다.
     */
    private void requireUnderNewInstallQuota(Optional<String> clientIp) {
        if (!ipQuotaEnabled || clientIp.isEmpty()) {
            return;
        }
        String key = IP_QUOTA_KEY_PREFIX + clientIp.get();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(1));
            }
            if (count != null && count > newInstallPerIpPerHour) {
                Long ttl = redisTemplate.getExpire(key);
                throw new TooManyRequestsException(ttl != null && ttl > 0 ? ttl : 3600);
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[ota] 신규 설치 상한 확인 실패 — 통과시킨다(fail-open)", e);
        }
    }
}
