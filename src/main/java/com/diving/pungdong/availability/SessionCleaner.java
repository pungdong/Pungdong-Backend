package com.diving.pungdong.availability;

import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 빈 일정 정리 — <b>session 존재 ⟺ 점유 &gt; 0</b> 불변식을 지킨다. 점유(활성 대기/결제대기/확정 신청 +
 * 외부/수동 hold)가 0 이 되면 그 일정을 삭제한다. 외부예약 hold 제거·신청 거절/취소로 0명이 된 카드를 없앤다.
 *
 * <p>과거 신청(취소/거절)은 {@code session_id} 만 끊고(enrollment 의 date/위치/블록 스냅샷은 보존) session
 * 을 지운다(FK 충돌 방지·이력 유지). coverage(예약가능시간)는 안 건드린다(독립 레이어).
 */
@Component
@RequiredArgsConstructor
public class SessionCleaner {

    private final AvailabilitySessionJpaRepo sessionRepo;
    private final EnrollmentJpaRepo enrollmentRepo;

    /** 점유 0 이면 삭제하고 true. 호출은 점유를 바꾼 트랜잭션 안에서(상태/ hold 변경이 flush 돼 보이도록). */
    public boolean deleteIfEmpty(AvailabilitySession session) {
        if (session == null) {
            return false;
        }
        boolean hasActive = !enrollmentRepo.findByAvailabilitySessionIdAndStatusIn(
                session.getId(), EnrollmentStatus.ACTIVE).isEmpty();
        if (hasActive || session.heldCount() > 0) {
            return false;
        }
        // 과거(취소/거절) 신청의 FK 만 끊고 스냅샷은 보존 → session 삭제(FK 충돌 방지).
        enrollmentRepo.findByAvailabilitySessionId(session.getId())
                .forEach(e -> e.setAvailabilitySession(null));
        sessionRepo.delete(session);
        return true;
    }
}
