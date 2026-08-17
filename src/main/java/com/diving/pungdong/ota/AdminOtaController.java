package com.diving.pungdong.ota;

import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.ota.dto.OtaDeviceSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OTA 릴리스 대시보드 (어드민 전용). 매처: {@code /admin/ota/**} hasRole(ADMIN).
 *
 * <p><b>BE 는 Cloudflare D1 을 읽지 않는다</b> — 번들 메타(메시지·커밋·enabled·rollout·force·플랫폼·지문·
 * 배포시각)는 D1 이 유일한 출처이고 어드민이 in-process 로 읽어 {@code bundleId} 키로 합친다. 여기선
 * <b>기기 카운트와 기기 목록만</b> 낸다. 라이브러리 D1 스키마가 8개월에 두 번 바뀐 이력이 있어, 컬럼명을
 * Java 에 새기면 업그레이드가 곧 사일런트 데이터 손상이 되기 때문이다.
 *
 * <p>숫자의 의미는 {@code docs/features/ota-telemetry.md} 의 카운트 정의표가 단일 출처다 —
 * 특히 {@code active}(윈도우 안에 "봤다"이지 "실행 중"이 아님)와 {@code crashRolledBack}(항상 하한, 단조 증가).
 */
@RestController
@RequestMapping(value = "/admin/ota", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminOtaController {

    private static final int MAX_ACTIVE_WINDOW_DAYS = 90;

    private final OtaAdminService otaAdminService;

    /**
     * 번들별 카운트.
     *
     * <p>{@code bundleIds} 를 주면 <b>그 id 만, 요청 순서 그대로, 없는 id 도 전부 0</b>(어드민이 zero-fill 을
     * 추측하지 않게 — 엔트리 누락은 상태가 아니라 버그다). 생략하면 BE 가 아는 <b>전량</b>을
     * {@code bundleId DESC} 로 — 이 모드가 있어야 어드민이 <b>D1 에 없는 고아 번들</b>(삭제됐는데 기기는 아직
     * 그 번들을 실행 중)을 찾을 수 있다.
     *
     * <p>페이지네이션이 없으므로 HAL 이 아니라 평문 JSON 이다.
     */
    @GetMapping(value = "/bundle-stats", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bundleStats(@RequestParam(required = false) String bundleIds,
                                         @RequestParam(required = false) String channel,
                                         @RequestParam(required = false) DeviceType platform,
                                         @RequestParam(required = false) Integer activeWindowDays) {
        List<String> ids = parseBundleIds(bundleIds);
        return ResponseEntity.ok(
                otaAdminService.bundleStats(ids, channel, platform, resolveWindow(activeWindowDays)));
    }

    /**
     * 번들의 기기 목록. {@code state} 기본값은 {@code ALL} — 첫 진입의 자연스러운 화면이 "이 번들의 기기
     * 전부"이고, {@code ACTIVE} 를 기본으로 두면 <b>윈도우 밖 기기가 조용히 빠진 목록</b>이 첫 화면이 된다.
     *
     * <p>없는 {@code bundleId} 는 404 가 아니라 <b>200 + 빈 페이지</b>다(정상 계산 결과는 200 + 필드).
     */
    @GetMapping("/bundles/{bundleId}/devices")
    public ResponseEntity<?> devicesOfBundle(@PathVariable String bundleId,
                                             @RequestParam(required = false) OtaDeviceState state,
                                             @RequestParam(required = false) Integer activeWindowDays,
                                             @PageableDefault(size = 20) Pageable pageable,
                                             PagedResourcesAssembler<OtaDeviceSummary> assembler) {
        OtaDeviceState resolved = state != null ? state : OtaDeviceState.ALL;
        return ResponseEntity.ok(assembler.toModel(otaAdminService.devicesOfBundle(
                bundleId, resolved, resolveWindow(activeWindowDays), pageable)));
    }

    /**
     * 문의 대응용 드릴다운 — {@code userId} <b>또는</b> {@code installId} 중 정확히 하나.
     *
     * <p>⚠️ 레포 규칙 "{@code userId} 파라미터는 red flag" 의 <b>정당한 예외</b>다: 여기서 {@code userId} 는
     * 요청자의 신분이 아니라 <b>조회 대상</b>이고, 요청자 신분은 매처가 검증한다. 기존
     * {@code /admin/instructor-applications/{id}} 와 같은 성격이다.
     */
    @GetMapping("/devices")
    public ResponseEntity<?> devices(@RequestParam(required = false) Long userId,
                                     @RequestParam(required = false) String installId,
                                     @PageableDefault(size = 20) Pageable pageable,
                                     PagedResourcesAssembler<OtaDeviceSummary> assembler) {
        return ResponseEntity.ok(assembler.toModel(
                otaAdminService.devicesByUserOrInstall(userId, installId, pageable)));
    }

    /** 대시보드 KPI + 앱버전/지문 분포. */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(required = false) String channel,
                                     @RequestParam(required = false) Integer activeWindowDays) {
        return ResponseEntity.ok(EntityModel.of(
                otaAdminService.summary(channel, resolveWindow(activeWindowDays))));
    }

    /* ─── 내부 ──────────────────────────────────────────────────────────── */

    private static List<String> parseBundleIds(String raw) {
        if (raw == null) {
            return null; // 전량 모드
        }
        List<String> ids = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            // 파라미터를 줬는데 유효한 id 가 하나도 없으면 "전량"으로 조용히 넘어가면 안 된다 —
            // 어드민이 의도한 건 좁히기였는데 전체가 나오면 화면이 거짓말한다.
            throw new BadRequestException("bundleIds 에 유효한 값이 없습니다.");
        }
        return ids;
    }

    private static int resolveWindow(Integer activeWindowDays) {
        if (activeWindowDays == null) {
            return OtaAdminService.DEFAULT_ACTIVE_WINDOW_DAYS;
        }
        if (activeWindowDays < 1 || activeWindowDays > MAX_ACTIVE_WINDOW_DAYS) {
            throw new BadRequestException("activeWindowDays 는 1~" + MAX_ACTIVE_WINDOW_DAYS + " 사이여야 합니다.");
        }
        return activeWindowDays;
    }
}
