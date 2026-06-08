package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.Gender;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 본인확인(간편인증) 결과 1건. 강사 신청의 전제 — 신청서는 VERIFIED 본인확인을 참조한다.
 *
 * <p>🔒 <b>deferred 외부 연동 경계.</b> 지금은 {@link StubIdentityVerifier} 가 mock CI/DI 로
 * 채운다. 실제 본인확인기관(KG이니시스/나이스) 연동이 들어오면 이 엔티티의 적재 경로만
 * 바뀌고 신청 도메인/컨트롤러는 그대로다. (memory: identity-verification-model)
 *
 * <p>⚠️ {@code ci}/{@code di} 는 고유식별정보다. stub 단계라 mock 값이지만, 실제 연동 시
 * <b>반드시 암호화 저장</b>으로 전환해야 한다 (현재는 평문 — 실데이터 적재 전 처리할 것).
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

    /** 주민등록상 실명 (PII). */
    private String realName;

    /** 생년월일 yyyyMMdd — Account.birth 와 동일 표기. */
    private String birth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    /** 본인 명의 휴대폰 번호 (PII). */
    private String phoneNumber;

    /** 연계정보 (CI). 본인확인기관 발급. stub 단계 = mock. */
    private String ci;

    /** 중복가입확인정보 (DI). stub 단계 = mock. */
    private String di;

    @Enumerated(EnumType.STRING)
    private IdentityProvider provider;

    private LocalDateTime verifiedAt;
}
