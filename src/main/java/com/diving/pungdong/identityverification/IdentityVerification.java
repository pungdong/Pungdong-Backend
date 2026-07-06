package com.diving.pungdong.identityverification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.Gender;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 본인확인 결과 1건. 강사 신청/결제의 전제 — 소비자는 {@code status==VERIFIED} 본인확인을 참조한다.
 *
 * <p><b>SMS 2단계 생명주기</b>: 생성(READY, OTP 발송) → 확인(VERIFIED/FAILED). 포트원 REST v2 로
 * 다날 휴대폰 본인인증을 호출하며, 우리 DB {@code id} 와 별개로 {@link #portoneVerificationId}
 * (우리가 발급한 포트원 {@code identityVerificationId})로 발송/확인을 매핑한다.
 *
 * <p>🔒 실 검증은 {@link IdentityVerifier} 경계(stub/disabled/real)에 위임 —
 * {@code pungdong.identity-verification.mode} 로 스왑. stub 은 mock CI/DI 로 채운다.
 *
 * <p>⚠️ {@code ci}/{@code di} 는 고유식별정보 — {@link CryptoStringConverter} 로 <b>암호화 저장</b>.
 */
@Entity
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class IdentityVerification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;

    /** 레코드 생명주기 상태 (READY/VERIFIED/FAILED). */
    @Enumerated(EnumType.STRING)
    private IdentityVerificationStatus status;

    /** 본인확인 방식 (SMS 실서비스 / APP 향후). */
    @Enumerated(EnumType.STRING)
    private IdentityVerificationMethod method;

    /** 포트원 {@code identityVerificationId} — 생성 시 {@code iv_<UUID>} 로 우리가 발급. 발송/확인 매핑키. */
    @Column(unique = true)
    private String portoneVerificationId;

    /** 주민등록상 실명 (PII). */
    private String realName;

    /** 생년월일 yyyyMMdd — Account.birth 와 동일 표기. */
    private String birth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    /** 본인 명의 휴대폰 번호 (PII). */
    private String phoneNumber;

    /**
     * 통신사. SMS 는 <b>요청 입력</b>(발송 대상), 확인 성공 시 포트원 반환 operator 로 덮어써 권위값.
     * 처리방침 본인인증 수집 항목과 1:1.
     */
    @Enumerated(EnumType.STRING)
    private Carrier carrier;

    /** 내·외국인 구분. 본인확인기관 반환 속성. stub 단계 = mock(DOMESTIC). */
    @Enumerated(EnumType.STRING)
    private ForeignerType foreignerType;

    /** 연계정보 (CI). 본인확인기관 발급 — 암호화 저장. */
    @Convert(converter = CryptoStringConverter.class)
    @Column(length = 512)
    private String ci;

    /** 중복가입확인정보 (DI) — 암호화 저장. */
    @Convert(converter = CryptoStringConverter.class)
    @Column(length = 512)
    private String di;

    /** 간편인증(APP) 공급자 — SMS 방식은 null. (향후 APP 방식 대비 유지) */
    @Enumerated(EnumType.STRING)
    private IdentityProvider provider;

    /** OTP 유효기한 — 발송/재발송 시 갱신. 만료 후 confirm 은 OTP_EXPIRED. */
    private LocalDateTime otpExpiresAt;

    /** OTP 확인 시도 횟수 — 초과 시 OTP_TOO_MANY_ATTEMPTS. */
    private int attemptCount;

    /** VERIFIED 로 전이한 시점. READY/FAILED 는 null. */
    private LocalDateTime verifiedAt;
}
