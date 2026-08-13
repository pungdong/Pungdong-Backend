package com.diving.pungdong.global.advice.exception;

/**
 * 환불을 <b>지금 처리할 수 없어</b> 그에 딸린 상태 전이(거절·취소·만료)를 확정하지 못할 때.
 *
 * <p><b>왜 필요한가</b>(C2): 환불 실행부는 "결과 미확인 시도({@code REQUESTED} 잔존)"가 있으면 이중환불을 막으려
 * 자동 환불을 건너뛴다. 그런데 예전엔 조용히 {@code return 0} 해서 <b>발행자(거절·취소·만료) 트랜잭션이 그대로
 * 커밋</b>됐다 — 회차는 REJECTED/CANCELLED 로 끝나는데 <b>돈은 안 나가고</b>, 재시도 주체도 없어 영구 미환불이 됐다.
 *
 * <p>이 예외를 던져 발행자 트랜잭션을 <b>롤백</b>시킨다 — "환불을 못 하면 거절·취소도 확정하지 않는다". 앞선
 * 미확인 시도를 사람이 PG 원장과 대사해 확정하면(또는 만료 스윕이 재시도하면) 그때 정상적으로 흐른다.
 */
public class RefundBlockedException extends RuntimeException {
    public RefundBlockedException(String message) {
        super(message);
    }
}
