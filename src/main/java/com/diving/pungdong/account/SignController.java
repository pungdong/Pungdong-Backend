package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.ValidationErrors;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.SignInInputException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.dto.emailCheck.EmailInfo;
import com.diving.pungdong.account.dto.emailCheck.EmailResult;
import com.diving.pungdong.account.dto.nickNameCheck.NickNameResult;
import com.diving.pungdong.account.dto.signIn.SignInInfo;
import com.diving.pungdong.account.dto.signUp.SignUpInfo;
import com.diving.pungdong.account.dto.signUp.SignUpResult;
import com.diving.pungdong.account.dto.auth.AuthToken;
import com.diving.pungdong.account.dto.auth.RefreshRequest;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.account.AccountService;
import com.diving.pungdong.account.InstructorCertificateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;


@RequiredArgsConstructor
@RestController
@RequestMapping(value = "/sign", produces = MediaTypes.HAL_JSON_VALUE)
public class SignController {
    private final AccountService accountService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** 같은 옛 RT 로 오는 동시/재유입 refresh 에 "같은 새 쌍"을 돌려주는 창(초). rotation 이 옛 RT 를 즉시
     *  블랙리스트에 넣기 때문에, 이 창이 없으면 동시 요청의 진 쪽이 401/403 → 세션이 죽는다(웹 이중소비 버그). */
    @Value("${pungdong.auth.refresh-idempotency-window-seconds:60}")
    private long refreshIdempotencyWindowSeconds;

    /** 회전 결과(새 토큰 쌍)를 옛 RT 로 키잉해 멱등 창 동안 저장하는 Redis 키 prefix. */
    private static final String ROTATED_KEY_PREFIX = "rotated:";

    @PostMapping("/check/email")
    public ResponseEntity<?> checkEmailExistence(@Valid @RequestBody EmailInfo emailInfo,
                                                 BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(ValidationErrors.firstMessage(result));
        }
        EmailResult emailResult = accountService.checkEmailExistence(emailInfo.getEmail());

        EntityModel<EmailResult> model = EntityModel.of(emailResult);
        model.add(linkTo(methodOn(SignController.class).checkEmailExistence(emailInfo, result)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-check-email").withRel("profile"));

        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/check/nickName")
    public ResponseEntity<?> checkDuplicationNickName(@NotEmpty @RequestParam String nickName) {
        NickNameResult nickNameResult = accountService.checkNickNameExistence(nickName);

        EntityModel<NickNameResult> model = EntityModel.of(nickNameResult);
        model.add(linkTo(methodOn(SignController.class).checkDuplicationNickName(nickName)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-check-duplication-nickName").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody SignInInfo signInInfo,
                                   BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }

        Account account = accountService.findAccountByEmail(signInInfo.getEmail());
        accountService.checkCorrectPassword(signInInfo.getPassword(), account);

        String accessToken = jwtTokenProvider.createAccessToken(
                String.valueOf(account.getId()), account.getRoles());
        String refreshToken = jwtTokenProvider.createRefreshToken(
                String.valueOf(account.getId()));

        AuthToken authToken = AuthToken.builder()
                .access_token(accessToken)
                .refresh_token(refreshToken)
                .token_type("bearer")
                .scope("read")
                .expires_in(jwtTokenProvider.getAccessTokenValiditySeconds())
                .jti(UUID.randomUUID().toString())
                .build();

        WebMvcLinkBuilder selfLinkBuilder = linkTo(methodOn(SignController.class).login(signInInfo, result));
        EntityModel<AuthToken> entityModel = EntityModel.of(authToken);
        entityModel.add(selfLinkBuilder.withSelfRel());
        entityModel.add(Link.of("/docs/api.html#resource-account-login").withRel("profile"));

        return ResponseEntity.ok().body(entityModel);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request,
                                     BindingResult result) {
        String oldRefreshToken = request.getRefreshToken();

        // 서명/만료 자체가 무효면 하드 거부.
        if (result.hasErrors() || !jwtTokenProvider.validateToken(oldRefreshToken)) {
            throw new com.diving.pungdong.global.advice.exception.ExpiredRefreshTokenException();
        }

        // 멱등: 이 RT 가 이미 회전됐다면(동시 요청 / 웹 멀티인스턴스·멀티탭) 같은 새 쌍을 그대로 돌려준다.
        // rotation 은 옛 RT 를 즉시 블랙리스트에 넣으므로, 이 검사가 블랙리스트 거부보다 "먼저" 와야
        // 막 회전된 RT 를 든 동시 요청이 401/403 으로 튕기지 않는다(세션 사망 방지).
        String rotatedKey = ROTATED_KEY_PREFIX + oldRefreshToken;
        String rotated = redisTemplate.opsForValue().get(rotatedKey);
        if (rotated != null) {
            return refreshResponse(request, result, deserializeAuthToken(rotated));
        }

        // 아직 회전 안 됨 — 로그아웃됐거나 멱등 창이 지난 뒤 재유입한 진짜 무효 RT 는 여기서 거부.
        if ("false".equals(redisTemplate.opsForValue().get(oldRefreshToken))) {
            throw new com.diving.pungdong.global.advice.exception.ExpiredRefreshTokenException();
        }

        String userPk = jwtTokenProvider.getUserPk(oldRefreshToken);
        Account account = accountService.findAccountById(Long.parseLong(userPk));

        // 탈퇴한 계정은 refresh 로 토큰을 재발급받을 수 없다(탈퇴 직후 access token 은 블랙리스트로
        // 막히고, 이 가드가 refresh 우회를 막는다 — 양쪽 다 닫아야 즉시 접근차단이 성립).
        if (Boolean.TRUE.equals(account.getIsDeleted())) {
            throw new com.diving.pungdong.global.advice.exception.ExpiredRefreshTokenException();
        }

        // 새 쌍 후보를 만든 뒤 setIfAbsent(원자적 SETNX)로 "회전 승자"를 선출한다. 동시 요청이 여럿이어도
        // 승자는 하나 — 나머지는 승자가 저장한 같은 쌍을 반환하므로 토큰 패밀리가 갈라지지 않는다.
        AuthToken candidate = issueTokens(account);
        Boolean won = redisTemplate.opsForValue().setIfAbsent(
                rotatedKey, serializeAuthToken(candidate),
                refreshIdempotencyWindowSeconds, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(won)) {
            // 동시 회전에 졌다 — 승자가 방금 저장한 같은 쌍을 반환(멱등).
            String winner = redisTemplate.opsForValue().get(rotatedKey);
            if (winner == null) {
                // 창 TTL 이 극히 드물게 경계에서 만료된 경우 — replay 로 간주해 거부.
                throw new com.diving.pungdong.global.advice.exception.ExpiredRefreshTokenException();
            }
            return refreshResponse(request, result, deserializeAuthToken(winner));
        }

        // 승자만: 옛 RT 를 남은 수명 동안 블랙리스트에 등록해 멱등 창 경과 후의 replay 를 차단한다.
        redisTemplate.opsForValue().set(oldRefreshToken, "false",
                jwtTokenProvider.getRefreshTokenValidMs(), TimeUnit.MILLISECONDS);

        return refreshResponse(request, result, candidate);
    }

    /** 새 access/refresh 쌍을 담은 AuthToken 을 발급한다 (로그인/가입과 같은 형태). */
    private AuthToken issueTokens(Account account) {
        return AuthToken.builder()
                .access_token(jwtTokenProvider.createAccessToken(
                        String.valueOf(account.getId()), account.getRoles()))
                .refresh_token(jwtTokenProvider.createRefreshToken(
                        String.valueOf(account.getId())))
                .token_type("bearer")
                .scope("read")
                .expires_in(jwtTokenProvider.getAccessTokenValiditySeconds())
                .jti(UUID.randomUUID().toString())
                .build();
    }

    private ResponseEntity<?> refreshResponse(RefreshRequest request, BindingResult result, AuthToken authToken) {
        EntityModel<AuthToken> model = EntityModel.of(authToken);
        model.add(linkTo(methodOn(SignController.class).refresh(request, result)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-refresh").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    private String serializeAuthToken(AuthToken authToken) {
        try {
            return objectMapper.writeValueAsString(authToken);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AuthToken 직렬화 실패", e);
        }
    }

    private AuthToken deserializeAuthToken(String json) {
        try {
            return objectMapper.readValue(json, AuthToken.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AuthToken 역직렬화 실패", e);
        }
    }

    @PostMapping(value = "/sign-up")
    public ResponseEntity<?> signUp(@Valid @RequestBody SignUpInfo signUpInfo,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new SignInInputException();
        }

        Account saved = accountService.saveAccountInfo(signUpInfo);

        // 가입과 동시에 로그인 — 클라이언트가 별도 /sign/login 호출 불필요
        String accessToken = jwtTokenProvider.createAccessToken(
                String.valueOf(saved.getId()), saved.getRoles());
        String refreshToken = jwtTokenProvider.createRefreshToken(
                String.valueOf(saved.getId()));
        AuthToken tokens = AuthToken.builder()
                .access_token(accessToken)
                .refresh_token(refreshToken)
                .token_type("bearer")
                .scope("read")
                .expires_in(jwtTokenProvider.getAccessTokenValiditySeconds())
                .jti(UUID.randomUUID().toString())
                .build();

        SignUpResult signUpResult = SignUpResult.builder()
                .email(saved.getEmail())
                .nickName(saved.getNickName())
                .tokens(tokens)
                .build();

        EntityModel<SignUpResult> model = EntityModel.of(signUpResult);
        WebMvcLinkBuilder selfLinkBuilder = linkTo(methodOn(SignController.class).signUp(signUpInfo, result));
        model.add(selfLinkBuilder.withSelfRel());
        model.add(Link.of("/docs/api.html#resource-account-create").withRel("profile"));

        return ResponseEntity.created(selfLinkBuilder.toUri()).body(model);
    }

    @PostMapping("/logout")
    public ResponseEntity logout(@RequestBody LogoutReq logoutReq) {
        // 블랙리스트 TTL 은 각 토큰의 유효기간과 맞춘다 (만료 전까지 무효 상태 유지 — 구멍 방지).
        redisTemplate.opsForValue().set(logoutReq.getAccessToken(), "false", jwtTokenProvider.getAccessTokenValidMs(), TimeUnit.MILLISECONDS);
        redisTemplate.opsForValue().set(logoutReq.getRefreshToken(), "false", jwtTokenProvider.getRefreshTokenValidMs(), TimeUnit.MILLISECONDS);

        EntityModel<LogoutRes> entity = EntityModel.of(new LogoutRes());
        entity.add(linkTo(methodOn(SignController.class).logout(logoutReq)).withSelfRel());
        entity.add(Link.of("/docs/api.html#resource-account-logout").withRel("profile"));

        return ResponseEntity.ok().body(entity);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class LogoutReq {
        String accessToken;
        String refreshToken;
    }

    @Data
    static class LogoutRes {
        String message = "로그아웃이 완료됐습니다";
    }
}
