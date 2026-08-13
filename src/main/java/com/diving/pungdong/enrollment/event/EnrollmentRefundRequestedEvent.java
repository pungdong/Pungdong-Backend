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
public record EnrollmentRefundRequestedEvent(Long roundId, String reason, boolean studentInitiated) {

    /**
     * 시스템 트리거(강사 거절 · 무응답 만료) — 기본값 {@code studentInitiated=false}.
     *
     * <p>이 구분은 <b>환불 완료 알림을 보낼지</b>를 가른다. 사용자 결정(2026-08-14)은 "환불 알림은 학생이
     * 직접 요청한 환불에만" 인데, 거절·만료는 그쪽 알림 body 가 이미 환불을 안내하므로 여기서 또 쏘면
     * 같은 사건에 알림이 2건 연속 간다. 반면 <b>학생 취소</b>는 학생이 스스로 한 요청이라 알려야 한다.
     *
     * <p>사유 문자열로 분기하지 않는 이유: 문구가 바뀌면 조용히 깨진다.
     */
    public EnrollmentRefundRequestedEvent(Long roundId, String reason) {
        this(roundId, reason, false);
    }
}
