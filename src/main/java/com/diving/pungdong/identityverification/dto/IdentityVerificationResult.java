package com.diving.pungdong.identityverification.dto;

import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
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
/**
 * <b>discriminated union (JSON)</b> — null 필드는 {@link JsonInclude.Include#NON_NULL} 로 <b>직렬화에서 제외</b>돼
 * 두 형태가 명확히 갈린다(FE 타입도 union 이라 필드 누락이 컴파일 타임에 잡힘):
 * <ul>
 *   <li><b>발송 성공</b>: {@code status=READY} + {@code verificationId}/{@code otpExpiresInSeconds}/{@code otpExpiresAt}
 *       (retryAfterSeconds 없음)</li>
 *   <li><b>발송 쿨다운</b>: {@code retryAfterSeconds} 만 (status·타이밍·id 없음 — SMS 미발송)</li>
 * </ul>
 * NON_NULL 이 없으면 쿨다운 응답에 성공 필드가 {@code null} 로 실려 union 이 거짓이 되므로 필수.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdentityVerificationResult {
    private Long verificationId;
    private IdentityVerificationStatus status;
    private LocalDateTime otpExpiresAt;
    /** OTP 잔여 초(발송 시점 기준). 카운트다운의 단일 출처 — 시계/TZ 무관. (영구 필드 — 글로벌화 후에도 유지) */
    private Long otpExpiresInSeconds;
    /**
     * 발송 쿨다운에 걸린 경우에만 존재 — 이 초만큼 뒤 재시도 가능(SMS 미발송, 다른 필드 없음).
     * 정상 발송이면 이 필드 없음(NON_NULL 로 생략). FE 는 이 필드 유무로 분기.
     */
    private Long retryAfterSeconds;
}
