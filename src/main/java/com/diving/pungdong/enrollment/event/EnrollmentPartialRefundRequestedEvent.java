package com.diving.pungdong.enrollment.event;

/**
 * 결제완료 회차의 <b>일부만 환불</b>이 필요해졌을 때 발행 — 선결제 회차의 슬롯이 <b>더 싼 슬롯으로 바뀐</b> 경우
 * (강사 제안 pick / 학생 재제안 reschedule)의 차액 반환.
 *
 * <p>전액 환불({@link EnrollmentRefundRequestedEvent})과 같은 이유로 이벤트다 — 환불 로직은 payment 도메인에 있고
 * enrollment 는 payment 를 import 하지 않는다. 발행자 트랜잭션 안에서 <b>동기</b> 실행되므로, PG 취소가 실패하면
 * 슬롯 변경까지 함께 롤백된다(돈-상태 원자성).
 *
 * @param roundId 회차 id
 * @param amount  환불할 차액(원, 양수)
 * @param reason  PG 에 남길 사유
 */
public record EnrollmentPartialRefundRequestedEvent(Long roundId, int amount, String reason) {
}
