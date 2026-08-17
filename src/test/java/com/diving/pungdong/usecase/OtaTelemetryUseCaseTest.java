package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.ota.OtaDevice;
import com.diving.pungdong.ota.OtaDeviceJpaRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OTA 텔레메트리 수집(`POST /app/ota/devices`, `/events`) 실행 스펙.
 *
 * <p>@DisplayName 을 위에서 아래로 읽으면 계약이 드러난다: 비로그인/로그인 부팅 upsert(S*), 이벤트 3종과
 * 레이스 허용(E*), 그리고 <b>관용적 검증</b>(V*) — 앱은 4xx 를 조용히 삼키므로 거절은 곧 "그 기기가 영구히
 * 집계 밖"이고, 그래서 보조 필드 하나 때문에 주 신호를 통째로 버리지 않는다.
 *
 * <p>실 스택(H2 + 실 시큐리티 체인 + 실 서비스), 최종 상태는 {@code OtaDeviceJpaRepo} 로 검증.
 * 정책·계약 = docs/features/ota-telemetry.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtaTelemetryUseCaseTest {

    private static final String DEVICES = "/app/ota/devices";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired OtaDeviceJpaRepo otaDeviceJpaRepo;

    @AfterEach
    void cleanUp() {
        otaDeviceJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
    }

    /* ─── S* 부팅 upsert ──────────────────────────────────────────────────── */

    @Test
    @DisplayName("S1: 비로그인 부팅 보고 → 200, 행 1개 생성되고 accountId 는 비어 있다")
    void upsert_anonymous_createsUnlinkedRow() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s1", "IOS", "1.0.0", "staging", "bundle-s1")))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-s1").orElseThrow();
        assertThat(saved.getAccountId()).isNull();
        assertThat(saved.getPlatform()).isEqualTo(DeviceType.IOS);
        assertThat(saved.getOtaBundleId()).isEqualTo("bundle-s1");
        assertThat(otaDeviceJpaRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2: 다음 부팅에 번들·앱버전이 갱신된다 (최초값에 고정되지 않는다)")
    void upsert_refreshesMutableFields() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s2", "IOS", "1.0.0", "staging", "bundle-old")))
                .andExpect(status().isOk());
        backdateLastSeen("install-s2");

        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s2", "IOS", "1.0.1", "staging", "bundle-new")))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-s2").orElseThrow();
        assertThat(saved.getOtaBundleId()).isEqualTo("bundle-new");
        assertThat(saved.getAppVersion()).isEqualTo("1.0.1");
        assertThat(otaDeviceJpaRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("S3: 생략한 필드는 기존 값이 유지된다 (null 로 덮어쓰지 않는다)")
    void upsert_omittedFieldsKeepPreviousValue() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s3", "IOS", "1.0.0", "staging", "bundle-s3")))
                .andExpect(status().isOk());
        backdateLastSeen("install-s3");

        // 앱이 필드별 try/catch 로 appVersion·otaBundleId 를 못 얻어 생략한 상황
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-s3\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-s3").orElseThrow();
        assertThat(saved.getAppVersion()).isEqualTo("1.0.0");
        assertThat(saved.getOtaBundleId()).isEqualTo("bundle-s3");
    }

    @Test
    @DisplayName("S4: 로그인 상태로 보고하면 계정이 연결되고, 이후 비로그인 보고가 그 연결을 지우지 않는다")
    void upsert_linksAccountAndKeepsItAfterLogout() throws Exception {
        Account me = createStudent("s4@test.com", "s4user");

        mockMvc.perform(post(DEVICES).header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s4", "ANDROID", "1.0.0", "staging", "bundle-s4")))
                .andExpect(status().isOk());
        assertThat(otaDeviceJpaRepo.findByInstallId("install-s4").orElseThrow().getAccountId())
                .isEqualTo(me.getId());

        backdateLastSeen("install-s4");
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s4", "ANDROID", "1.0.0", "staging", "bundle-s4")))
                .andExpect(status().isOk());

        // 로그아웃해도 "누구 기기였는지"가 남아야 CS 드릴다운이 끊기지 않는다.
        assertThat(otaDeviceJpaRepo.findByInstallId("install-s4").orElseThrow().getAccountId())
                .isEqualTo(me.getId());
    }

    @Test
    @DisplayName("S5: otaBundleId 가 otaMinBundleId 와 같으면 내장 번들로 보고 null 로 저장한다")
    void upsert_normalizesEmbeddedBundle() throws Exception {
        String body = "{\"installId\":\"install-s5\",\"platform\":\"IOS\",\"appVersion\":\"1.0.0\","
                + "\"otaChannel\":\"staging\",\"otaBundleId\":\"same-uuid\",\"otaMinBundleId\":\"same-uuid\"}";

        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-s5").orElseThrow();
        assertThat(saved.getOtaBundleId()).isNull();
        assertThat(saved.getOtaMinBundleId()).isEqualTo("same-uuid");
    }

    @Test
    @DisplayName("S6: 60초 안에 다시 보고하면 조용히 무시한다 (429 가 아니라 정상 200)")
    void upsert_mergesWithinWindow() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s6", "IOS", "1.0.0", "staging", "bundle-a")))
                .andExpect(status().isOk());

        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-s6", "IOS", "9.9.9", "staging", "bundle-b")))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-s6").orElseThrow();
        assertThat(saved.getAppVersion()).isEqualTo("1.0.0");
        assertThat(saved.getOtaBundleId()).isEqualTo("bundle-a");
    }

    /* ─── E* 이벤트 ───────────────────────────────────────────────────────── */

    @Test
    @DisplayName("E1: 기기 행이 아직 없을 때 이벤트가 먼저 도착해도 200 — 최소 행을 만들어 신호를 보존한다")
    void event_beforeUpsert_createsMinimalRow() throws Exception {
        mockMvc.perform(post(DEVICES + "/install-e1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DOWNLOADED\",\"bundleId\":\"bundle-e1\"}"))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-e1").orElseThrow();
        assertThat(saved.getDownloadedBundleId()).isEqualTo("bundle-e1");
        assertThat(saved.getPlatform()).isNull(); // 다음 부팅 upsert 가 채운다
    }

    @Test
    @DisplayName("E2: DOWNLOADED / SERVER_ROLLBACK / CRASH_ROLLBACK 이 각자의 컬럼에 기록된다")
    void event_threeTypesWriteOwnColumns() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(bootBody("install-e2", "IOS", "1.0.0", "staging", "bundle-cur")))
                .andExpect(status().isOk());

        postEvent("install-e2", "DOWNLOADED", "bundle-dl");
        postEvent("install-e2", "SERVER_ROLLBACK", "bundle-disabled");
        postEvent("install-e2", "CRASH_ROLLBACK", "bundle-crashed");

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-e2").orElseThrow();
        assertThat(saved.getDownloadedBundleId()).isEqualTo("bundle-dl");
        assertThat(saved.getServerRollbackFromBundleId()).isEqualTo("bundle-disabled");
        assertThat(saved.getCrashRollbackBundleId()).isEqualTo("bundle-crashed");
        // 크래시 '보고' 시각 — 실제 크래시 시각이 아니다(이전 실행에서 났고 관측 불가).
        assertThat(saved.getCrashRollbackReportedAt()).isNotNull();
    }

    @Test
    @DisplayName("E3: 같은 종류 이벤트를 다시 보내면 마지막 값으로 덮어쓴다 (원장이 아니라 최신 상태)")
    void event_lastWriteWins() throws Exception {
        postEvent("install-e3", "DOWNLOADED", "bundle-first");
        backdateDownloadedAt("install-e3");
        postEvent("install-e3", "DOWNLOADED", "bundle-second");

        assertThat(otaDeviceJpaRepo.findByInstallId("install-e3").orElseThrow().getDownloadedBundleId())
                .isEqualTo("bundle-second");
    }

    /* ─── V* 검증 ─────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("V1: installId 가 안전하지 않은 문자를 담으면 400 (경로변수로도 쓰이는 값이라 문자셋을 막는다)")
    void reject_unsafeInstallId() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"bad/../id\",\"platform\":\"IOS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2: appVersion·fingerprintHash 를 못 보내도 200 — 그 기기를 집계에서 지우지 않는다")
    void accept_missingOptionalFields() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v2\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-v2").orElseThrow();
        assertThat(saved.getAppVersion()).isNull();
        assertThat(saved.getFingerprintHash()).isNull();
    }

    @Test
    @DisplayName("V3: 두 자리 버전('1.0')도 받는다 — iOS 는 두 자리 버전이 합법이다")
    void accept_twoSegmentAppVersion() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v3\",\"platform\":\"IOS\",\"appVersion\":\"1.0\"}"))
                .andExpect(status().isOk());

        assertThat(otaDeviceJpaRepo.findByInstallId("install-v3").orElseThrow().getAppVersion())
                .isEqualTo("1.0");
    }

    @Test
    @DisplayName("V4: 64자 지문도 받는다 — 해시 알고리즘이 바뀌어도 전 기기가 사라지지 않게")
    void accept_longerFingerprint() throws Exception {
        String sha256 = "a".repeat(64);
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v4\",\"platform\":\"IOS\",\"fingerprintHash\":\""
                                + sha256 + "\"}"))
                .andExpect(status().isOk());

        assertThat(otaDeviceJpaRepo.findByInstallId("install-v4").orElseThrow().getFingerprintHash())
                .isEqualTo(sha256);
    }

    @Test
    @DisplayName("V5: 크래시 이력에 이상한 원소가 섞여도 200 — 그 원소만 버리고 앱버전 같은 주 신호는 지킨다")
    void crashHistory_dropsBadEntriesKeepsMainSignal() throws Exception {
        String tooLong = "x".repeat(50);
        String body = "{\"installId\":\"install-v5\",\"platform\":\"IOS\",\"appVersion\":\"1.0.0\","
                + "\"otaCrashHistory\":[\"bundle-ok-1\",\"" + tooLong + "\",\"bundle-ok-2\"]}";

        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        OtaDevice saved = otaDeviceJpaRepo.findByInstallId("install-v5").orElseThrow();
        assertThat(saved.getAppVersion()).isEqualTo("1.0.0"); // 주 신호가 살아남았다
        assertThat(saved.getCrashHistory()).contains("bundle-ok-1", "bundle-ok-2");
        assertThat(saved.getCrashHistory()).doesNotContain(tooLong);
    }

    @Test
    @DisplayName("V6: 크래시 이력이 20개를 넘으면 상한만큼만 저장한다")
    void crashHistory_capsAtLimit() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            entries.append(i > 0 ? "," : "").append("\"bundle-").append(i).append("\"");
        }
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v6\",\"platform\":\"IOS\",\"otaCrashHistory\":["
                                + entries + "]}"))
                .andExpect(status().isOk());

        String stored = otaDeviceJpaRepo.findByInstallId("install-v6").orElseThrow().getCrashHistory();
        assertThat(stored.split("\",\"").length).isEqualTo(20);
    }

    @Test
    @DisplayName("V7: 코호트는 숫자 문자열이든 커스텀 슬러그든 원문 그대로 저장한다")
    void cohort_storedVerbatim() throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v7a\",\"platform\":\"IOS\",\"otaCohort\":\"742\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-v7b\",\"platform\":\"IOS\",\"otaCohort\":\"qa\"}"))
                .andExpect(status().isOk());

        assertThat(otaDeviceJpaRepo.findByInstallId("install-v7a").orElseThrow().getOtaCohort()).isEqualTo("742");
        assertThat(otaDeviceJpaRepo.findByInstallId("install-v7b").orElseThrow().getOtaCohort()).isEqualTo("qa");
    }

    /* ─── 헬퍼 ────────────────────────────────────────────────────────────── */

    private void postEvent(String installId, String type, String bundleId) throws Exception {
        mockMvc.perform(post(DEVICES + "/" + installId + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"bundleId\":\"" + bundleId + "\"}"))
                .andExpect(status().isOk());
    }

    private static String bootBody(String installId, String platform, String appVersion,
                                   String channel, String bundleId) {
        return "{\"installId\":\"" + installId + "\",\"platform\":\"" + platform + "\",\"appVersion\":\""
                + appVersion + "\",\"otaChannel\":\"" + channel + "\",\"otaBundleId\":\"" + bundleId + "\"}";
    }

    /** 병합 창(60초)을 벗어난 "다음 부팅"을 만든다. */
    private void backdateLastSeen(String installId) {
        OtaDevice device = otaDeviceJpaRepo.findByInstallId(installId).orElseThrow();
        device.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        otaDeviceJpaRepo.saveAndFlush(device);
    }

    /** 이벤트 병합 창(10초)을 벗어난 재보고를 만든다. */
    private void backdateDownloadedAt(String installId) {
        OtaDevice device = otaDeviceJpaRepo.findByInstallId(installId).orElseThrow();
        device.setDownloadedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        otaDeviceJpaRepo.saveAndFlush(device);
    }

    private Account createStudent(String email, String nick) {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        return accountJpaRepo.save(Account.builder()
                .email(email).password(passwordEncoder.encode("1234")).nickName(nick)
                .phoneNumber("01012345678").birth("1990-01-01").gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).profilePhoto(photo)
                .build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }
}
