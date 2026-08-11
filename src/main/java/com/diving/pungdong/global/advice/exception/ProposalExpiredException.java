package com.diving.pungdong.global.advice.exception;

/**
 * 강사가 낸 <b>일정 제안이 더 이상 유효하지 않을 때</b> 그걸 고르려 한 경우.
 *
 * <p><b>왜 전용 코드인가</b> — 만료는 <b>사용자 잘못이 아니고</b> 회복 동선이 명확하다("제안이 만료됐어요 ·
 * 일정을 직접 골라보세요"). 그런데 범용 {@code -1011} 의 문구는 "보내신 요청 정보가 옳지 않습니다." 라,
 * 그대로 노출하면 사용자에게 아무 의미가 없고 자기 잘못처럼 읽힌다.
 *
 * <p><b>언제 이 상태가 되나</b> — 제안 슬롯은 {@code proposalTtlHours}(기본 6h) 가 지나면
 * {@code EnrollmentExpiryService.sweepExpiredProposals} 가 보장 hold 를 풀고 {@code proposedSlots} 를
 * 비운다. 회차 상태 자체는 그대로 {@code ACCEPT_PENDING}(강사 결정 대기)이라 <b>학생은 그냥 일정을 직접
 * 고르면 된다</b>({@code reschedule}).
 *
 * <p><b>범위</b> — "제안이 없다"({@code hasRescheduleOffer()} 실패)까지만 이 코드다. 제안은 살아 있는데
 * <b>목록 밖 슬롯</b>을 고른 경우(강사가 그새 다른 슬롯으로 재제안 등)는 성격이 달라 {@code -1011} 을 유지한다.
 */
public class ProposalExpiredException extends RuntimeException {
    public ProposalExpiredException() {
        super();
    }
}
