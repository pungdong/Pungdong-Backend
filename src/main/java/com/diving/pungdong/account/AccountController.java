package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.ValidationErrors;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.controller.lectureImage.LectureImageController;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.InstructorCertificate;
import com.diving.pungdong.account.dto.instructor.certificate.InstructorCertificateInfo;
import com.diving.pungdong.account.dto.restore.AccountRestoreInfo;
import com.diving.pungdong.account.dto.update.AccountUpdateInfo;
import com.diving.pungdong.account.dto.update.ForgotPasswordInfo;
import com.diving.pungdong.account.dto.update.NickNameInfo;
import com.diving.pungdong.account.dto.update.PasswordUpdateInfo;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.account.InstructorCertificateService;
import com.diving.pungdong.account.AccountService;
import com.diving.pungdong.account.dto.read.AccountBasicInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping(value = "/account")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;
    private final InstructorCertificateService instructorCertificateService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping
    public ResponseEntity<?> readAccountInfo(@CurrentUser Account account) {
        AccountBasicInfo accountBasicInfo = accountService.mapToAccountBasicInfo(account);

        EntityModel<AccountBasicInfo> model = EntityModel.of(accountBasicInfo);
        model.add(linkTo(methodOn(AccountController.class).readAccountInfo(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-read").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/instructor/certificate/list")
    public ResponseEntity<?> readInstructorCertificates(@CurrentUser Account account) {
        List<InstructorCertificate> instructorCertificateList = instructorCertificateService.findInstructorCertificates(account);
        List<InstructorCertificateInfo> instructorCertificateInfos = instructorCertificateService.mapToInstructorCertificateInfos(instructorCertificateList);

        CollectionModel<InstructorCertificateInfo> model = CollectionModel.of(instructorCertificateInfos);
        model.add(linkTo(methodOn(AccountController.class).readInstructorCertificates(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-instructor-certificate-read-list").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PutMapping
    public ResponseEntity<?> updateAccountInfo(@CurrentUser Account account,
                                               @Valid @RequestBody AccountUpdateInfo updateInfo,
                                               BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }

        accountService.updateAccountInfo(account, updateInfo);

        EntityModel<SuccessResult> model = EntityModel.of(new SuccessResult(true));
        model.add(linkTo(methodOn(AccountController.class).updateAccountInfo(account, updateInfo, result)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-update").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PatchMapping("/nickName")
    public ResponseEntity<?> updateAccountNickName(@CurrentUser Account account,
                                                   @Valid @RequestBody NickNameInfo nickNameInfo,
                                                   BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }

        accountService.updateNickName(account, nickNameInfo.getNickName());

        EntityModel<SuccessResult> model = EntityModel.of(new SuccessResult(true));
        model.add(linkTo(methodOn(AccountController.class).updateAccountNickName(account, nickNameInfo, result)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-update-nickName").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PatchMapping("/password")
    public ResponseEntity<?> updateAccountPassword(@CurrentUser Account account,
                                                   @Valid @RequestBody PasswordUpdateInfo passwordUpdateInfo,
                                                   BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(ValidationErrors.firstMessage(result));
        }

        accountService.updatePassword(account, passwordUpdateInfo);

        EntityModel<SuccessResult> model = EntityModel.of(new SuccessResult(true));
        model.add(linkTo(methodOn(AccountController.class).updateAccountPassword(account, passwordUpdateInfo, result)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-update-password").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    // 탈퇴는 로그인 세션에서만 호출되므로 세션(JWT) 자체가 본인 증명 — 비밀번호 재확인을 받지 않는다.
    // (표준 패턴은 재인증이 아니라 FE 의 "의도 확인"; soft delete + 30일 복구가 실수/악의 삭제의 안전망이고,
    //  "탈퇴 ≤ 가입 난이도" 원칙·소셜로그인(비번 없음) 대응. 결정 히스토리는 docs/features/account-deletion.md.)
    // 구버전 앱이 body 에 {password} 를 동봉해도 무시 — @RequestBody 를 받지 않으므로 그대로 204.
    @DeleteMapping
    public ResponseEntity<?> removeAccount(@CurrentUser Account account,
                                           HttpServletRequest request) {
        accountService.deleteAccount(account);

        // 탈퇴 즉시 현재 access token 을 블랙리스트(로그아웃과 동일 경로) — 이미 발급된 토큰이
        // 만료 전까지 살아있는 구멍을 막는다. refresh 재발급은 SignController.refresh 의 탈퇴 가드가 차단.
        String accessToken = jwtTokenProvider.resolveToken(request);
        if (accessToken != null) {
            redisTemplate.opsForValue().set(accessToken, "false",
                    jwtTokenProvider.getAccessTokenValidMs(), TimeUnit.MILLISECONDS);
        }

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/deleted-state")
    public ResponseEntity<?> restoreAccount(@Valid @RequestBody AccountRestoreInfo accountRestoreInfo,
                                            BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(ValidationErrors.firstMessage(result));
        }

        accountService.updateAccountDeleted(accountRestoreInfo);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/forgot-password")
    public ResponseEntity<?> updateForgotPassword(@Valid @RequestBody ForgotPasswordInfo forgotPasswordInfo,
                                                  BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(ValidationErrors.firstMessage(result));
        }

        accountService.modifyForgetPassword(forgotPasswordInfo);

        return ResponseEntity.ok().build();
    }
}