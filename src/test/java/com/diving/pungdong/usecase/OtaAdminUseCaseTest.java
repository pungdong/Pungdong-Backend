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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드민 OTA 릴리스 대시보드(`/admin/ota/**`) 실행 스펙.
 *
 * <p>@DisplayName 을 위에서 아래로 읽으면 계약이 드러난다: 권한(R*)과 집계 규약(A*). 핵심은
 * <b>목록의 숫자와 상세 필터의 결과가 항상 같은 술어를 쓴다</b>는 것(A7) — 어드민이 숫자를 눌렀는데 다른 수가
 * 나오면 사용자가 바로 알아차린다.
 *
 * <p>번들 메타(메시지·커밋·enabled·rollout)는 여기 없다 — Cloudflare D1 이 유일한 출처이고 어드민이 합친다.
 * 정책·계약 = docs/features/ota-telemetry.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtaAdminUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired OtaDeviceJpaRepo otaDeviceJpaRepo;

    @AfterEach
    void cleanUp() {
        otaDeviceJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
    }

    /* ─── R* 권한 ─────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("R1: 어드민이 아닌 사용자가 대시보드를 열면 403")
    void nonAdmin_isForbidden() throws Exception {
        Account student = createAccount("r1@test.com", "r1user", Role.STUDENT);

        mockMvc.perform(get("/admin/ota/summary").header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R2: 로그인하지 않고 대시보드를 열면 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/ota/summary"))
                .andExpect(status().isUnauthorized());
    }

    /* ─── A* 집계 ─────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("A1: 물어본 번들은 아직 아무도 안 받은 것까지 전부 응답에 들어간다 (요청한 순서 그대로, 없으면 0)")
    void bundleStats_zeroFillsEveryRequestedIdInOrder() throws Exception {
        seedDevice("d1", "bundle-known", null, null, null, "staging", DeviceType.IOS, recent());

        mockMvc.perform(get("/admin/ota/bundle-stats")
                        .param("bundleIds", "bundle-zzz,bundle-known,bundle-never-seen")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.length()").value(3))
                .andExpect(jsonPath("$.stats[0].bundleId").value("bundle-zzz"))
                .andExpect(jsonPath("$.stats[0].active").value(0))
                .andExpect(jsonPath("$.stats[1].bundleId").value("bundle-known"))
                .andExpect(jsonPath("$.stats[1].active").value(1))
                .andExpect(jsonPath("$.stats[2].bundleId").value("bundle-never-seen"))
                .andExpect(jsonPath("$.stats[2].installed").value(0))
                .andExpect(jsonPath("$.activeWindowDays").value(7));
    }

    @Test
    @DisplayName("A2: 한 번에 100개를 넘겨 물어보면 400")
    void bundleStats_rejectsTooManyIds() throws Exception {
        String tooMany = IntStream.range(0, 101).mapToObj(i -> "b" + i).collect(Collectors.joining(","));

        mockMvc.perform(get("/admin/ota/bundle-stats").param("bundleIds", tooMany)
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A3: 물어볼 id 를 안 주면 아는 번들 전량을 최신순으로 준다 (D1 에서 사라진 번들을 찾는 통로)")
    void bundleStats_fullModeReturnsAllDescending() throws Exception {
        seedDevice("d1", "bundle-a", null, null, null, "staging", DeviceType.IOS, recent());
        seedDevice("d2", "bundle-c", null, null, null, "staging", DeviceType.IOS, recent());
        seedDevice("d3", "bundle-b", null, null, null, "staging", DeviceType.IOS, recent());

        mockMvc.perform(get("/admin/ota/bundle-stats").header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.length()").value(3))
                .andExpect(jsonPath("$.stats[0].bundleId").value("bundle-c"))
                .andExpect(jsonPath("$.stats[1].bundleId").value("bundle-b"))
                .andExpect(jsonPath("$.stats[2].bundleId").value("bundle-a"));
    }

    @Test
    @DisplayName("A4: '받았는데 안 켠' 은 이미 켠 기기를 빼고 센다 (총 도달량과는 다른 숫자다)")
    void downloadedNotInstalled_excludesAlreadyRunning() throws Exception {
        // 받고 아직 안 켠 기기
        seedDevice("waiting", null, "bundle-x", null, null, "staging", DeviceType.IOS, recent());
        // 받아서 이미 켠 기기
        seedDevice("running", "bundle-x", "bundle-x", null, null, "staging", DeviceType.IOS, recent());

        mockMvc.perform(get("/admin/ota/bundle-stats").param("bundleIds", "bundle-x")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats[0].downloaded").value(2))
                .andExpect(jsonPath("$.stats[0].downloadedNotInstalled").value(1))
                .andExpect(jsonPath("$.stats[0].installed").value(1));
    }

    @Test
    @DisplayName("A5: 기기가 다음 번들로 넘어가면 이전 번들의 '실행 중' 수에서 빠진다 (누적이 아니다)")
    void installed_isCurrentStateNotCumulative() throws Exception {
        OtaDevice device = seedDevice("mover", "bundle-old", null, null, null, "staging",
                DeviceType.IOS, recent());

        mockMvc.perform(get("/admin/ota/bundle-stats").param("bundleIds", "bundle-old")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(jsonPath("$.stats[0].installed").value(1));

        device.setOtaBundleId("bundle-new");
        otaDeviceJpaRepo.saveAndFlush(device);

        mockMvc.perform(get("/admin/ota/bundle-stats").param("bundleIds", "bundle-old,bundle-new")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(jsonPath("$.stats[0].installed").value(0))
                .andExpect(jsonPath("$.stats[1].installed").value(1));
    }

    @Test
    @DisplayName("A6: 기기 목록의 기본 화면은 그 번들과 엮인 기기 전부다 (활성만 보여주면 오래된 기기가 조용히 빠진다)")
    void deviceList_defaultsToAll() throws Exception {
        seedDevice("running", "bundle-y", null, null, null, "staging", DeviceType.IOS, recent());
        seedDevice("stale", "bundle-y", null, null, null, "staging", DeviceType.IOS, longAgo());
        seedDevice("downloaded-only", null, "bundle-y", null, null, "staging", DeviceType.IOS, recent());
        seedDevice("rolled-back", null, null, "bundle-y", null, "staging", DeviceType.IOS, recent());

        mockMvc.perform(get("/admin/ota/bundles/bundle-y/devices")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // ACTIVE 로 좁히면 오래된 기기가 빠진다 — 그래서 기본값이 아니다.
        mockMvc.perform(get("/admin/ota/bundles/bundle-y/devices").param("state", "ACTIVE")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("A7: 목록의 숫자를 누르면 그 숫자만큼 나온다 (카운트와 상세 필터가 같은 술어를 쓴다)")
    void counts_matchDeviceListSizes() throws Exception {
        seedDevice("a", "bundle-z", "bundle-z", null, null, "staging", DeviceType.IOS, recent());
        seedDevice("b", null, "bundle-z", null, null, "staging", DeviceType.IOS, recent());
        seedDevice("c", null, null, "bundle-z", null, "staging", DeviceType.IOS, recent());
        seedDevice("d", null, null, null, "bundle-z", "staging", DeviceType.IOS, recent());
        seedCrashHistoryDevice("e", "bundle-z", "staging");

        MvcResult stats = mockMvc.perform(get("/admin/ota/bundle-stats").param("bundleIds", "bundle-z")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk()).andReturn();
        String body = stats.getResponse().getContentAsString();

        assertStateMatchesCount(body, "active", "ACTIVE");
        assertStateMatchesCount(body, "installed", "INSTALLED");
        assertStateMatchesCount(body, "downloaded", "DOWNLOADED");
        assertStateMatchesCount(body, "downloadedNotInstalled", "DOWNLOADED_NOT_INSTALLED");
        assertStateMatchesCount(body, "serverRolledBack", "SERVER_ROLLED_BACK");
        // 크래시는 컬럼 일치 + 이력 배열 포함을 함께 센다 — 두 표현이 갈리면 여기서 잡힌다.
        assertStateMatchesCount(body, "crashRolledBack", "CRASH_ROLLED_BACK");
    }

    @Test
    @DisplayName("A8: 없는 번들의 기기 목록은 404 가 아니라 빈 목록 (정상 계산 결과다)")
    void unknownBundle_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/admin/ota/bundles/no-such-bundle/devices")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("A9: 요약의 분포 합계는 활성 기기 수와 맞고, 내장 번들 기기도 모수에 들어간다")
    void summary_distributionsSumToActiveDevices() throws Exception {
        seedDevice("s1", "bundle-p", null, null, null, "staging", DeviceType.IOS, recent());
        seedDevice("s2", "bundle-p", null, null, null, "staging", DeviceType.ANDROID, recent());
        seedDevice("s3", null, null, null, null, "staging", DeviceType.IOS, recent()); // 내장(OTA 미수신)
        seedDevice("s4", "bundle-p", null, null, null, "staging", DeviceType.IOS, longAgo()); // 윈도우 밖

        MvcResult result = mockMvc.perform(get("/admin/ota/summary").param("channel", "staging")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeDevices").value(3))
                .andExpect(jsonPath("$.embeddedDevices").value(1))
                .andReturn();

        long versionTotal = sumCounts(result.getResponse().getContentAsString(), "byAppVersion");
        assertThat(versionTotal).isEqualTo(3);
    }

    @Test
    @DisplayName("A10: 유저 드릴다운은 userId 또는 installId 중 하나만 받는다 (둘 다 주면 400)")
    void drilldown_requiresExactlyOneKey() throws Exception {
        mockMvc.perform(get("/admin/ota/devices").header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/ota/devices").param("userId", "1").param("installId", "x")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest());
    }

    /* ─── 헬퍼 ────────────────────────────────────────────────────────────── */

    /** 카운트 N 이면 그 state 목록도 정확히 N 건이어야 한다. */
    private void assertStateMatchesCount(String statsBody, String countField, String state) throws Exception {
        long expected = readLong(statsBody, countField);
        MvcResult list = mockMvc.perform(get("/admin/ota/bundles/bundle-z/devices").param("state", state)
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk()).andReturn();
        long actual = readLong(list.getResponse().getContentAsString(), "totalElements");
        assertThat(actual).as("state=%s 목록 건수가 %s 카운트와 같아야 한다", state, countField)
                .isEqualTo(expected);
    }

    private static long readLong(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":");
        if (idx < 0) {
            throw new IllegalStateException(field + " 없음: " + json);
        }
        int start = idx + field.length() + 3;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static long sumCounts(String json, String arrayField) {
        int from = json.indexOf("\"" + arrayField + "\":");
        int end = json.indexOf(']', from);
        String slice = json.substring(from, end);
        long sum = 0;
        int idx = 0;
        while ((idx = slice.indexOf("\"count\":", idx)) >= 0) {
            int s = idx + 8;
            int e = s;
            while (e < slice.length() && Character.isDigit(slice.charAt(e))) {
                e++;
            }
            sum += Long.parseLong(slice.substring(s, e));
            idx = e;
        }
        return sum;
    }

    private OtaDevice seedDevice(String installId, String running, String downloaded, String serverRollback,
                                 String crashRollback, String channel, DeviceType platform,
                                 OffsetDateTime lastSeen) {
        return otaDeviceJpaRepo.saveAndFlush(OtaDevice.builder()
                .installId(installId)
                .platform(platform)
                .appVersion("1.0.0")
                .otaChannel(channel)
                .otaBundleId(running)
                .downloadedBundleId(downloaded)
                .downloadedAt(downloaded != null ? lastSeen : null)
                .serverRollbackFromBundleId(serverRollback)
                .serverRollbackAt(serverRollback != null ? lastSeen : null)
                .crashRollbackBundleId(crashRollback)
                .crashRollbackReportedAt(crashRollback != null ? lastSeen : null)
                .lastSeenAt(lastSeen)
                .createdAt(lastSeen)
                .build());
    }

    /** 크래시 롤백 컬럼은 비었지만 <b>이력 배열</b>에만 그 번들이 있는 기기 — 두 술어의 합집합을 검증한다. */
    private void seedCrashHistoryDevice(String installId, String bundleId, String channel) {
        otaDeviceJpaRepo.saveAndFlush(OtaDevice.builder()
                .installId(installId)
                .platform(DeviceType.IOS)
                .appVersion("1.0.0")
                .otaChannel(channel)
                .crashHistory("[\"" + bundleId + "\"]")
                .lastSeenAt(recent())
                .createdAt(recent())
                .build());
    }

    private static OffsetDateTime recent() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
    }

    private static OffsetDateTime longAgo() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
    }

    private String adminToken() {
        Account admin = accountJpaRepo.findByEmail("ota-admin@test.com")
                .orElseGet(() -> createAccount("ota-admin@test.com", "otaadmin", Role.ADMIN));
        return tokenFor(admin);
    }

    private Account createAccount(String email, String nick, Role role) {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        return accountJpaRepo.save(Account.builder()
                .email(email).password(passwordEncoder.encode("1234")).nickName(nick)
                .phoneNumber("01012345678").birth("1990-01-01").gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(role))).profilePhoto(photo)
                .build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }
}
