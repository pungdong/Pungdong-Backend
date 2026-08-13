package com.diving.pungdong.global.advice.exception;

/**
 * 동시 요청이 DB 유니크 제약에 걸려 진 경우 — 일시적 경합이라 재시도하면 대개 해결된다.
 *
 * <p>대표 사례: 동시 {@code POST /payments/prepare} 둘이 같은 회차에 READY 주문을 만들려다
 * {@code uk_payment_order_ready_round}(회차당 READY 1개)에 걸림. 같은 트랜잭션에선 제약 위반 후 재조회가
 * 안전하지 않아(rollback-only) 이 요청은 롤백하고, 클라이언트가 재시도하면 먼저 만들어진 주문을 재사용한다.
 *
 * <p>{@code ObjectOptimisticLockingFailureException}(낙관적 락 충돌)과 같은 성격이라 응답도 동일하게
 * <b>409 / -1021 CONCURRENT_MODIFICATION</b> — "요청이 겹쳤어요, 잠시 후 다시 시도" 로 안내한다.
 */
public class ConcurrentRequestException extends RuntimeException {
    public ConcurrentRequestException(Throwable cause) {
        super(cause);
    }
}
