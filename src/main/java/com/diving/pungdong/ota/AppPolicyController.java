package com.diving.pungdong.ota;

import com.diving.pungdong.ota.dto.AppPolicyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 앱 최소버전 정책 — <b>공개 조회</b>. 매처 {@code GET /app/policy} → permitAll.
 *
 * <p>OTA 는 JS 만 바꾸므로 네이티브가 바뀐 릴리스 이후엔 스토어 업데이트를 강제할 수단이 없다. 앱이 부팅 시
 * 이걸 읽어 {@code getAppVersion() < minVersion} 이면 차단 화면을 띄운다. semver 비교는 앱이 한다.
 *
 * <p>🚨 <b>어떤 경우에도 401 을 내지 않는다.</b> 앱은 공개 엔드포인트도 <b>같은 axios 인스턴스</b>로 부르는
 * 관례라(토큰이 동봉된다), 이 엔드포인트가 한 번이라도 401 을 내면 그 인스턴스의 401 인터셉터가
 * {@code setUnauthenticated()} 를 호출해 <b>부팅 중에 정상 사용자가 강제 로그아웃</b>된다. permitAll 이라
 * 원래 안 나오지만, 실제 리스크는 <b>만료 토큰이 붙어 왔을 때</b>다 — use-case 테스트 {@code P3} 가 그걸 잠근다.
 *
 * <p>앱 측도 fail-open 이다: 조회 실패(오프라인/타임아웃/5xx)는 <b>통과</b>시킨다.
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AppPolicyController {

    /** 정책 변경 반영이 최대 이만큼 지연된다 — 게이트를 올리는 일은 드물어 허용. */
    private static final long CACHE_SECONDS = 300;

    private final AppPolicyService appPolicyService;

    @GetMapping("/policy")
    public ResponseEntity<AppPolicyResponse> get() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .body(appPolicyService.get());
    }
}
