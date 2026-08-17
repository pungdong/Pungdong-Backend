package com.diving.pungdong.ota;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.ota.dto.AppPolicyResponse;
import com.diving.pungdong.ota.dto.UpdateAppPolicyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 앱 정책 편집 (어드민 전용). 매처 {@code /admin/app/**} → hasRole(ADMIN).
 *
 * <p><b>전체 치환</b>이다(PATCH 아님) — 어드민 폼이 전 필드를 다시 보낸다.
 *
 * <p>여기의 semver 검증은 <b>엄격하다</b>({@code 1.0.0} 3자리 고정). 텔레메트리의 {@code appVersion} 이
 * {@code 1.0} 을 허용하는 것과의 비대칭은 <b>의도된 것</b>이다 — 저긴 관측이라 거절하면 기기가 조용히
 * 사라지고, 여긴 제어라 형식이 깨진 값이 저장되면 <b>앱의 비교가 조용히 틀린다</b>(전원 차단 또는 전원 통과).
 * 그리고 여긴 사람이 폼에 넣고 에러 메시지가 화면에 보인다.
 */
@RestController
@RequestMapping("/admin/app")
@RequiredArgsConstructor
public class AdminAppPolicyController {

    private final AppPolicyService appPolicyService;

    @PutMapping("/policy")
    public ResponseEntity<AppPolicyResponse> update(@CurrentUser Account admin,
                                                    @Valid @RequestBody UpdateAppPolicyRequest request,
                                                    BindingResult result) {
        if (result.hasErrors()) {
            // 어떤 필드가 왜 틀렸는지 한국어로 돌려준다 — 어드민이 그대로 읽는다.
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok(appPolicyService.update(request, admin));
    }
}
