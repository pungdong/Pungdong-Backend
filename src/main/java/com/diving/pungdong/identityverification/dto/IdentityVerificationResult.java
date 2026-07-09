package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 본인확인 생성(=SMS 발송) 결과 (201). OTP 발송 직후라 {@code status=READY} — FE 는 이
 * {@code verificationId} 로 {@code POST /{id}/confirm} 을 호출한다.
 *
 * <p><b>카운트다운은 {@code otpExpiresInSeconds} 를 쓰라</b> — 발송 시점 기준 OTP 잔여 초. 서버가 계산한
 * 상대값이라 클라이언트 TZ·기기 시계 오차와 무관하다(TTL 은 stub 180s / real 300s, 향후 변동 가능하니 FE 가
 * 하드코딩하지 말 것). {@code otpExpiresAt} 은 표시/디버그용 절대시각(서버 KST wall-clock).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class IdentityVerificationResult {
    private Long verificationId;
    private IdentityVerificationStatus status;
    private LocalDateTime otpExpiresAt;
    /** OTP 잔여 초(발송 시점 기준). 카운트다운의 단일 출처 — 시계/TZ 무관. */
    private Long otpExpiresInSeconds;
}
