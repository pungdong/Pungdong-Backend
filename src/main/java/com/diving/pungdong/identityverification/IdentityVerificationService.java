package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.identityverification.IdentityVerifier.ConfirmResult;
import com.diving.pungdong.identityverification.IdentityVerifier.SendCommand;
import com.diving.pungdong.identityverification.IdentityVerifier.SendResult;
import com.diving.pungdong.identityverification.IdentityVerifier.VerifiedCustomer;
import com.diving.pungdong.identityverification.dto.ConfirmIdentityVerificationResult;
import com.diving.pungdong.identityverification.dto.IdentityVerificationRequest;
import com.diving.pungdong.identityverification.dto.IdentityVerificationResult;
import com.diving.pungdong.identityverification.dto.MyIdentityVerificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 본인확인 도메인 서비스 — SMS 2단계(발송/확인) 오케스트레이션 + 영속화. 외부 호출은
 * {@link IdentityVerifier} 경계(stub/disabled/real)에 위임한다. 계정 공유 자산 — 수강/강사 어느
 * 플로우에서든 같은 레코드를 만들고 읽는다.
 *
 * <p>OTP <b>만료·시도초과</b> 정책은 여기서 강제(모든 구현 공통) — 경계는 "이 OTP 가 맞는가"만.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityVerificationService {

    /** OTP 확인 최대 시도 횟수 — 초과 시 레코드 FAILED(세션 종료, 재발송/재시작 필요). */
    private static final int MAX_ATTEMPTS = 5;

    private final IdentityVerifier identityVerifier;
    private final IdentityVerificationJpaRepo identityVerificationRepo;
    private final AccountJpaRepo accountRepo;

    /** 생성 + OTP 발송(결합). 발송 실패 시 트랜잭션 롤백 — 고아 READY 레코드를 남기지 않는다. */
    @Transactional
    public IdentityVerificationResult create(Account account, IdentityVerificationRequest request) {
        Account managed = accountRepo.findById(account.getId())
                .orElseThrow(ResourceNotFoundException::new);

        IdentityVerificationMethod method =
                request.getMethod() != null ? request.getMethod() : IdentityVerificationMethod.SMS;
        String portoneId = "iv_" + UUID.randomUUID();

        IdentityVerification v = identityVerificationRepo.save(IdentityVerification.builder()
                .account(managed)
                .status(IdentityVerificationStatus.READY)
                .method(method)
                .portoneVerificationId(portoneId)
                .realName(request.getRealName())
                .birth(request.getBirth())
                .gender(request.getGender())
                .phoneNumber(request.getPhoneNumber())
                .carrier(request.getCarrier())
                .provider(request.getProvider())
                .attemptCount(0)
                .build());

        SendResult sent = identityVerifier.send(new SendCommand(
                portoneId, request.getRealName(), request.getBirth(), request.getGender(),
                request.getPhoneNumber(), request.getCarrier(), method));
        v.setOtpExpiresAt(sent.otpExpiresAt());

        return sendResult(v);
    }

    /** OTP 확인. 소유권 검증 → 멱등(이미 VERIFIED) → 만료/시도 선판정 → 경계 확인. */
    @Transactional
    public ConfirmIdentityVerificationResult confirm(Account account, Long verificationId, String otp) {
        IdentityVerification v = requireMine(account, verificationId);

        if (v.getStatus() == IdentityVerificationStatus.VERIFIED) {
            return result(v, null); // 멱등
        }
        if (v.getStatus() == IdentityVerificationStatus.FAILED) {
            return result(v, IdentityVerificationErrorCode.OTP_TOO_MANY_ATTEMPTS); // 종료된 세션
        }
        // READY
        if (v.getOtpExpiresAt() != null && v.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            return result(v, IdentityVerificationErrorCode.OTP_EXPIRED); // 재발송 필요 (레코드는 READY 유지)
        }
        if (v.getAttemptCount() >= MAX_ATTEMPTS) {
            v.setStatus(IdentityVerificationStatus.FAILED);
            return result(v, IdentityVerificationErrorCode.OTP_TOO_MANY_ATTEMPTS);
        }

        v.setAttemptCount(v.getAttemptCount() + 1);
        ConfirmResult outcome = identityVerifier.confirm(v.getPortoneVerificationId(), otp);
        if (outcome.status() == IdentityVerificationStatus.VERIFIED) {
            applyVerified(v, outcome.customer());
            return result(v, null);
        }
        // 불일치 — 시도 소진 시 세션 FAILED, 아니면 READY 유지(재입력 허용).
        if (v.getAttemptCount() >= MAX_ATTEMPTS) {
            v.setStatus(IdentityVerificationStatus.FAILED);
        }
        return result(v, outcome.errorCode() != null ? outcome.errorCode()
                : IdentityVerificationErrorCode.OTP_MISMATCH);
    }

    /** OTP 재발송 — 시도 카운트/상태를 초기화하고 새 OTP 를 보낸다(FAILED 세션도 되살림). */
    @Transactional
    public IdentityVerificationResult resend(Account account, Long verificationId) {
        IdentityVerification v = requireMine(account, verificationId);
        if (v.getStatus() == IdentityVerificationStatus.VERIFIED) {
            throw new BadRequestException("이미 본인확인이 완료된 요청입니다.");
        }
        SendResult sent = identityVerifier.resend(v.getPortoneVerificationId());
        v.setStatus(IdentityVerificationStatus.READY);
        v.setAttemptCount(0);
        v.setOtpExpiresAt(sent.otpExpiresAt());
        return sendResult(v);
    }

    /**
     * 발송/재발송 응답 빌더 — {@code otpExpiresInSeconds}(잔여 초)를 서버에서 계산해 함께 내린다.
     * FE 카운트다운의 단일 출처(클라이언트 시계/TZ 무관). 절대시각 {@code otpExpiresAt}(표시/디버그용)도 유지.
     */
    private IdentityVerificationResult sendResult(IdentityVerification v) {
        Long seconds = v.getOtpExpiresAt() == null ? null
                : Math.max(0, Duration.between(LocalDateTime.now(), v.getOtpExpiresAt()).getSeconds());
        return IdentityVerificationResult.builder()
                .verificationId(v.getId())
                .status(v.getStatus())
                .otpExpiresAt(v.getOtpExpiresAt())
                .otpExpiresInSeconds(seconds)
                .build();
    }

    /** 계정의 최신 VERIFIED 상태 — skip/재사용 판단. 없으면 {verified:false} (404 아님, 무만료). */
    public MyIdentityVerificationResponse getMyVerification(Account account) {
        return identityVerificationRepo
                .findTopByAccountIdAndStatusOrderByIdDesc(account.getId(), IdentityVerificationStatus.VERIFIED)
                .map(v -> MyIdentityVerificationResponse.builder()
                        .verified(true)
                        .verificationId(v.getId())
                        .realName(v.getRealName())
                        .provider(v.getProvider())
                        .verifiedAt(v.getVerifiedAt())
                        .build())
                .orElseGet(MyIdentityVerificationResponse::notVerified);
    }

    /* ─── 내부 ─────────────────────────────────────────── */

    /** 소유권 검증 — 비소유/없음은 400(존재 숨김, payment/instructor 와 동일 결). */
    private IdentityVerification requireMine(Account account, Long verificationId) {
        IdentityVerification v = identityVerificationRepo.findById(verificationId)
                .orElseThrow(BadRequestException::new);
        if (v.getAccount() == null || !v.getAccount().getId().equals(account.getId())) {
            throw new BadRequestException();
        }
        return v;
    }

    /** VERIFIED 전이 — 기관 반환값을 요청 입력 위에 덮어써 권위값으로, CI/DI 적재. */
    private void applyVerified(IdentityVerification v, VerifiedCustomer customer) {
        v.setStatus(IdentityVerificationStatus.VERIFIED);
        v.setVerifiedAt(LocalDateTime.now());
        v.setForeignerType(ForeignerType.DOMESTIC); // 실 내외국인 판별은 개통 후 보정
        if (customer != null) {
            v.setCi(customer.ci());
            v.setDi(customer.di());
            if (customer.realName() != null) {
                v.setRealName(customer.realName());
            }
            if (customer.phoneNumber() != null) {
                v.setPhoneNumber(customer.phoneNumber());
            }
            if (customer.carrier() != null) {
                v.setCarrier(customer.carrier());
            }
        }
    }

    /**
     * confirm 응답 status 는 이 시도의 <b>결과</b>(VERIFIED|FAILED)로 좁힌다 — 엔티티가 재입력
     * 허용을 위해 내부적으로 READY 를 유지해도, FE 에는 이번 확인이 성공/실패인지만 내려간다.
     */
    private ConfirmIdentityVerificationResult result(IdentityVerification v, IdentityVerificationErrorCode errorCode) {
        boolean verified = v.getStatus() == IdentityVerificationStatus.VERIFIED;
        return ConfirmIdentityVerificationResult.builder()
                .verificationId(v.getId())
                .status(verified ? IdentityVerificationStatus.VERIFIED : IdentityVerificationStatus.FAILED)
                .realName(verified ? v.getRealName() : null)
                .errorCode(errorCode)
                .build();
    }
}
