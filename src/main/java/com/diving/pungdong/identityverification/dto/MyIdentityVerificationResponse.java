package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.identityverification.IdentityProvider;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 내 본인확인 상태 — 계정 공유 자산. 수강/강사 등 어느 플로우에서 인증했든 같은 레코드를 가리킨다.
 * 미인증도 404 가 아니라 200 {@code {verified:false}} ({@link MyIdentityVerificationResponse#notVerified()}).
 *
 * <p>강사 신청 진입 시 FE 가 이걸 호출해 {@code verified} 면 본인확인 단계를 건너뛰고(skip),
 * {@code verificationId} 를 신청 제출에 재사용한다.
 *
 * <p>{@code verifiedAt} 은 노출하되 현재 만료 판단엔 쓰지 않는다(무만료). 법적 재인증 주기 정책이
 * 정해지면 그 위에 TTL 을 얹는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class MyIdentityVerificationResponse {

    private boolean verified;
    private Long verificationId;       // 미인증 시 null
    private String realName;           // 미인증 시 null
    private IdentityProvider provider; // 미인증 시 null
    private OffsetDateTime verifiedAt;  // 미인증 시 null

    public static MyIdentityVerificationResponse notVerified() {
        return MyIdentityVerificationResponse.builder().verified(false).build();
    }
}
