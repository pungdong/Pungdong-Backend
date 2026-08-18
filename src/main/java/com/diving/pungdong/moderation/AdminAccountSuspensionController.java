package com.diving.pungdong.moderation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 계정 정지/해제 (ROLE_ADMIN).
 *
 * <p><b>신고 처리(PATCH /admin/reports/{id})와 분리된 경로다.</b> 기각(DISMISSED)은 조치를 되돌리는
 * 동작이 아니고(글도 다시 공개하지 않는다), 정지는 사람이 서비스를 아예 못 쓰는 상태라
 * <b>되돌릴 문이 없으면 영구 잠금</b>이 된다. 그래서 해제를 명시적 엔드포인트로 둔다.
 *
 * <p>대상은 <b>닉네임</b>이다 — 순차 계정 id 를 계약에 노출하지 않는다(anti-IDOR).
 * ⚠️ 닉네임에 {@code /} 가 들어가면 {@code StrictHttpFirewall} 이 막는 기존 한계를 그대로 물려받는다.
 */
@RestController
@RequestMapping(value = "/admin/accounts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminAccountSuspensionController {

    private final ContentReportService reportService;

    /** {@code {suspended: false}} 로 해제, {@code true} 로 수동 정지. 204. */
    @PatchMapping("/{nickName}/suspension")
    public ResponseEntity<?> suspension(@PathVariable String nickName,
                                        @RequestBody SuspensionRequest request) {
        reportService.setSuspended(nickName, request.isSuspended());
        return ResponseEntity.noContent().build();
    }

    @Getter @Setter
    public static class SuspensionRequest {
        private boolean suspended;
    }
}
