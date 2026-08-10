package com.diving.pungdong.enrollment;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 미결제 회차의 <b>결제창(window)</b> 계산 — 순수 함수 모음.
 *
 * <p>기한은 <b>저장되지 않는다</b>. {@code createdAt + paymentTtlHours}(Sanity 런타임값)로 그때그때 푼다.
 * 만료를 실제로 집행하는 {@link EnrollmentExpiryService} 의 스윕 조건과 <b>같은 식</b>을 쓰게 여기 한 곳에
 * 모아, 화면에 보여주는 카운트다운과 서버가 실제로 자르는 시점이 어긋나지 않게 한다.
 *
 * <p><b>왜 잔여 초를 내려보내나</b>: 절대시각을 내리면 클라이언트 기기 시계가 틀어진 만큼 카운트다운이
 * 통째로 밀린다. 서버가 계산한 상대값이면 TZ·시계 오차와 무관하다 — {@code otpExpiresInSeconds} 와 같은
 * 이유이고 {@code docs/architecture/time-handling.md} 의 규칙이다.
 *
 * <p>⚠️ <b>0 이 곧 "결제 불가"는 아니다.</b> 만료 스윕은 주기 폴링(기본 5분)이라 기한이 지나도 잠깐은
 * {@code PENDING} 이고 결제가 성사될 수 있다(성사되면 {@code ACCEPT_PENDING} 이 되어 스윕 대상에서 빠지므로
 * "결제했는데 취소되는" 구멍은 없다). FE 는 0 을 "곧 만료"로 다루면 된다.
 */
public final class PaymentWindow {

    private PaymentWindow() {}

    /** 미결제 회차의 결제 기한 — 신청 시각 + TTL. {@code createdAt} 이 없으면 null. */
    public static OffsetDateTime deadline(OffsetDateTime createdAt, int paymentTtlHours) {
        return createdAt == null ? null : createdAt.plusHours(paymentTtlHours);
    }

    /**
     * 기한까지 남은 초(음수는 0으로 clamp). 기한이 없으면 null.
     *
     * @return 잔여 초, 또는 계산 불가 시 {@code null}
     */
    public static Long remainingSeconds(OffsetDateTime deadline, OffsetDateTime now) {
        if (deadline == null) {
            return null;
        }
        return Math.max(0L, Duration.between(now, deadline).getSeconds());
    }

    /**
     * <b>미결제({@code PENDING}) 회차</b>의 잔여 결제 초. 그 상태가 아니면 {@code null} — 카운트다운을 띄울
     * 상황이 아니라는 뜻이다(결제 완료·확정·취소·거절).
     */
    public static Long remainingSecondsFor(EnrollmentRound round, int paymentTtlHours, OffsetDateTime now) {
        if (round == null || round.getStatus() != EnrollmentStatus.PENDING) {
            return null;
        }
        return remainingSeconds(deadline(round.getCreatedAt(), paymentTtlHours), now);
    }
}
