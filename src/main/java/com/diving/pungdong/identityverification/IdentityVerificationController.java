package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
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
 * 본인확인(간편인증) — 계정 공유 자산. 수강/강사 어느 플로우든 여기서 본인확인을 만들고(POST)
 * 조회한다(GET /me). 강사 신청은 GET /me 로 기존 인증을 확인해 재인증을 건너뛴다(skip).
 *
 * <p>권한 매처: {@code /identity-verifications/**} → authenticated.
 */
@RestController
@RequestMapping(value = "/identity-verifications", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;

    /** 본인확인 생성 (간편인증 stub). PII 는 POST body. */
    @PostMapping
    public ResponseEntity<?> verify(@CurrentUser Account account,
                                    @Valid @RequestBody IdentityVerificationRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        IdentityVerificationResult verification = identityVerificationService.verify(account, request);

        EntityModel<IdentityVerificationResult> model = EntityModel.of(verification);
        model.add(linkTo(methodOn(IdentityVerificationController.class).getMine(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-identity-verification-create").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /** 내 최신 본인확인 상태 — skip 판단 + verificationId 재사용. 미인증도 200 {verified:false}. */
    @GetMapping("/me")
    public ResponseEntity<?> getMine(@CurrentUser Account account) {
        MyIdentityVerificationResponse response = identityVerificationService.getMyVerification(account);

        EntityModel<MyIdentityVerificationResponse> model = EntityModel.of(response);
        model.add(linkTo(methodOn(IdentityVerificationController.class).getMine(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-identity-verification-me").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
