package com.diving.pungdong.ota;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.ota.dto.OtaDeviceUpsertRequest;
import com.diving.pungdong.ota.dto.OtaEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 앱 OTA 텔레메트리 수집 — <b>인증 불필요</b>({@code SecurityConfiguration} 의 permitAll 매처).
 *
 * <p><b>왜 permitAll 인가</b>: 로그인·푸시 권한과 무관하게 모든 설치를 세야 릴리스 대시보드가 의미를 갖는다.
 * 인증을 요구하면 "잘못된 번들에 갇혀서 로그아웃한 사용자" — 가장 보고 싶은 집단 — 가 먼저 사라진다.
 *
 * <p>신분은 여전히 <b>세션에서만</b> 온다: {@code @CurrentUser} 는 익명이면 null 을 주므로, JWT 를 실어
 * 보내면 그 계정이 링크되고 안 실으면 비로그인 기기로 남는다. <b>바디로 accountId 를 받지 않는다.</b>
 *
 * <p>🔒 {@code installId} 는 암호학적 난수가 아니다 — <b>이 값을 키로 하는 비인증 '읽기' 경로를 만들지 말 것.</b>
 * (현재 읽는 경로는 {@code GET /admin/ota/devices?installId=} 하나뿐이고 ADMIN 뒤에 있다.)
 */
@RestController
@RequestMapping("/app/ota/devices")
@RequiredArgsConstructor
public class OtaTelemetryController {

    private final OtaTelemetryService telemetryService;
    private final OtaClientIpResolver clientIpResolver;

    /** 부팅 1회 + 포그라운드 재체크(4h 스로틀) upsert. 60초 안 재호출은 조용히 no-op 200. */
    @PostMapping
    public ResponseEntity<Void> upsert(@CurrentUser Account account,
                                       @Valid @RequestBody OtaDeviceUpsertRequest request,
                                       BindingResult result,
                                       HttpServletRequest httpRequest) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        telemetryService.upsert(request, account, clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok().build();
    }

    /**
     * 이벤트 보고. {@code installId} 행이 아직 없어도 <b>200</b>(서버가 최소 행을 만든다) —
     * 부팅 upsert 와의 완료 순서가 보장되지 않아 404 로 막으면 이벤트가 유실된다.
     */
    @PostMapping("/{installId}/events")
    public ResponseEntity<Void> recordEvent(@PathVariable String installId,
                                            @Valid @RequestBody OtaEventRequest request,
                                            BindingResult result,
                                            HttpServletRequest httpRequest) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        requireSafeInstallId(installId);
        telemetryService.recordEvent(installId, request, clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok().build();
    }

    /**
     * 경로변수 {@code installId} 의 안전 문자셋 검사.
     *
     * <p>바디 DTO 와 같은 제약이지만 {@code @Pattern} 을 파라미터에 달지 않는다 — 그러려면 클래스에
     * {@code @Validated} 가 필요하고, 그러면 {@code ConstraintViolationException} 이 나와 이 레포에 핸들러가
     * 없는 예외 경로(500)가 생긴다. 여기선 직접 검사해 기존 {@code BadRequestException}(400/-1011)으로 모은다.
     *
     * <p>형식 강제가 아니라 <b>안전 한계</b>다 — permitAll 경로변수라 {@code /}·CRLF·공백 유입을 막는다.
     */
    private static void requireSafeInstallId(String installId) {
        if (installId == null || !INSTALL_ID_SAFE.matcher(installId).matches()) {
            throw new BadRequestException("installId 형식이 올바르지 않습니다.");
        }
    }

    private static final java.util.regex.Pattern INSTALL_ID_SAFE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
}
