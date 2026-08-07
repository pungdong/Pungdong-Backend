package com.diving.pungdong.enrollment.event;

/**
 * 결제완료(ACCEPT_PENDING) 회차의 <b>환불이 필요해졌을 때</b> 발행 — 강사 거절 또는 무응답 만료(선결제).
 *
 * <p><b>왜 이벤트인가</b>: 환불 로직은 payment 도메인에 있고 enrollment 는 payment 를 import 하지 않는다
 * (역참조 방지). enrollment 가 이 이벤트를 발행하면 payment 의 {@code @EventListener} 가 수신해 환불한다
 * (payment→enrollment 는 허용 방향). 이벤트 클래스를 enrollment 에 두어 payment 만 이걸 import 한다.
 *
 * <p><b>동기 처리</b>: 기본 {@code @EventListener} 는 발행자의 트랜잭션 안에서 동기 실행된다 — 환불(PG 취소)이
 * 실패하면 예외가 전파돼 상태변경(REJECTED/CANCELLED)도 함께 롤백된다(원자성: 환불 성공해야 상태도 바뀜).
 */
public record EnrollmentRefundRequestedEvent(Long roundId, String reason) {
}
