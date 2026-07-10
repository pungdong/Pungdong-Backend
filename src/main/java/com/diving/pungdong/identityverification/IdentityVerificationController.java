package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.identityverification.dto.ConfirmIdentityVerificationRequest;
import com.diving.pungdong.identityverification.dto.ConfirmIdentityVerificationResult;
import com.diving.pungdong.identityverification.dto.IdentityVerificationRequest;
import com.diving.pungdong.identityverification.dto.IdentityVerificationResult;
import com.diving.pungdong.identityverification.dto.MyIdentityVerificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 본인확인(휴대폰 SMS, 포트원/다날) — 계정 공유 자산. 수강/강사 어느 플로우든 여기서 본인확인을
 * 만들고(POST=발송) OTP 를 확인한다(confirm). 강사 신청은 {@code GET /me} 로 기존 인증을 확인해
 * 재인증을 건너뛴다(skip).
 *
 * <p>SMS 2단계: {@code POST} (생성+발송) → {@code POST /{id}/confirm} (OTP) → VERIFIED.
 * 재발송은 {@code POST /{id}/resend}. 권한 매처: {@code /identity-verifications/**} → authenticated.
 */
@RestController
@RequestMapping(value = "/identity-verifications", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;

    /** 생성 + OTP 발송(결합). PII 는 POST body. → 201 {verificationId, status:READY, otpExpiresAt}. */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody IdentityVerificationRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        IdentityVerificationResult created = identityVerificationService.create(account, request);

        if (created.getRetryAfterSeconds() != null) {
            // 발송 쿨다운 — SMS 미발송·레코드 미생성. 201/confirm 링크 아님(정상 분기 200 + retryAfterSeconds).
            return ResponseEntity.ok(EntityModel.of(created));
        }
        EntityModel<IdentityVerificationResult> model = EntityModel.of(created);
        model.add(confirmLink(account, created.getVerificationId()));
        model.add(Link.of("/docs/api.html#resource-identity-verification-create").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /** OTP 확인. → 200 {verificationId, status:VERIFIED|FAILED, realName?, errorCode?}. */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@CurrentUser Account account,
                                     @PathVariable Long id,
                                     @Valid @RequestBody ConfirmIdentityVerificationRequest request,
                                     BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        ConfirmIdentityVerificationResult confirmed =
                identityVerificationService.confirm(account, id, request.getOtp());

        EntityModel<ConfirmIdentityVerificationResult> model = EntityModel.of(confirmed);
        model.add(linkTo(methodOn(IdentityVerificationController.class).getMine(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-identity-verification-confirm").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** OTP 재발송. → 200 {verificationId, status:READY, otpExpiresAt}. */
    @PostMapping("/{id}/resend")
    public ResponseEntity<?> resend(@CurrentUser Account account, @PathVariable Long id) {
        IdentityVerificationResult resent = identityVerificationService.resend(account, id);

        if (resent.getRetryAfterSeconds() != null) {
            return ResponseEntity.ok(EntityModel.of(resent)); // 쿨다운 — confirm 링크 없이 {retryAfterSeconds}
        }
        EntityModel<IdentityVerificationResult> model = EntityModel.of(resent);
        model.add(confirmLink(account, id));
        model.add(Link.of("/docs/api.html#resource-identity-verification-resend").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 내 최신 VERIFIED 상태 — skip 판단 + verificationId 재사용. 미인증도 200 {verified:false}. */
    @GetMapping("/me")
    public ResponseEntity<?> getMine(@CurrentUser Account account) {
        MyIdentityVerificationResponse response = identityVerificationService.getMyVerification(account);

        EntityModel<MyIdentityVerificationResponse> model = EntityModel.of(response);
        model.add(linkTo(methodOn(IdentityVerificationController.class).getMine(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-identity-verification-me").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    private Link confirmLink(Account account, Long id) {
        return linkTo(methodOn(IdentityVerificationController.class)
                .confirm(account, id, null, null)).withRel("confirm");
    }
}
