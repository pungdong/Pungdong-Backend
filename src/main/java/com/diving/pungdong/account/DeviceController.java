package com.diving.pungdong.account;

import com.diving.pungdong.account.dto.DeviceRegisterRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 현재 로그인 사용자의 디바이스(FCM 토큰) 등록/해제.
 *
 * <p>벤더 비종속 리소스 네이밍(`/me/devices`) — 옛 `/sign/firebase-token`(auth 네임스페이스 + 벤더명)
 * 을 대체. 신분은 항상 세션(`@CurrentUser`)에서, 바디/파라미터로 받지 않는다. 정책/계약은
 * docs/features/push.md, 발송 파이프라인은 docs/architecture/notification.md.
 */
@RestController
@RequestMapping("/me/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final FirebaseTokenService firebaseTokenService;

    /** 토큰 등록(= upsert, 재등록 시 갱신). 로그인 후 / onTokenRefresh 때 호출. */
    @PostMapping
    public ResponseEntity<Void> register(@CurrentUser Account account,
                                         @Valid @RequestBody DeviceRegisterRequest request,
                                         BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        firebaseTokenService.register(account, request.getToken(), request.getPlatform());
        return ResponseEntity.ok().build();
    }

    /** 토큰 해제. 로그아웃 / 회원탈퇴 시 호출 (account-deletion 흐름과 연동). */
    @DeleteMapping("/{token}")
    public ResponseEntity<Void> unregister(@CurrentUser Account account, @PathVariable String token) {
        firebaseTokenService.unregister(account, token);
        return ResponseEntity.noContent().build();
    }
}
