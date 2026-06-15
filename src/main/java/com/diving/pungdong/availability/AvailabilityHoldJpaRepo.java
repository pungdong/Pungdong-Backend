package com.diving.pungdong.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvailabilityHoldJpaRepo extends JpaRepository<AvailabilityHold, Long> {

    /** 한 window 의 점유 hold 들 — 점유 상태를 트랜잭션 밖에서 확인할 때(테스트·집계). */
    List<AvailabilityHold> findByWindowId(Long windowId);
}
