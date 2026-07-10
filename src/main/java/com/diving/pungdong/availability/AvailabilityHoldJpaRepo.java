package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface AvailabilityHoldJpaRepo extends JpaRepository<AvailabilityHold, Long> {

    /** 한 일정(session)의 점유 hold 들 — 점유 상태를 트랜잭션 밖에서 확인할 때(테스트·집계). */
    List<AvailabilityHold> findBySessionId(Long sessionId);

    /** 한 회차의 강사 제안 보장 hold 들 — 학생 pick/재제안/취소 시 일괄 해제용. */
    List<AvailabilityHold> findByProposalRoundId(Long proposalRoundId);

    /** 만료된 제안 hold 들 — TTL sweep(학생 미선택). 회차 귀속이고 expiresAt 이 cutoff 이전인 것. */
    List<AvailabilityHold> findByProposalRoundIdIsNotNullAndExpiresAtBefore(OffsetDateTime cutoff);
}
