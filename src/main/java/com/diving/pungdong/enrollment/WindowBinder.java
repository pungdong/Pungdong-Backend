package com.diving.pungdong.enrollment;

import com.diving.pungdong.availability.AvailabilityWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * availability window 의 (venueRefId, sessionLabel) bind/unbind — enrollment 가 window 를 "어느 세션으로 찼나"
 * 표시·exact-match 의 단일 출처. 첫 active enrollment 가 bind, 활성이 0 이 되면 unbind(다시 available).
 * EnrollmentService(신청·취소)와 InstructorEnrollmentService(거절)가 공유.
 */
@Component
@RequiredArgsConstructor
public class WindowBinder {

    private final EnrollmentJpaRepo enrollmentRepo;

    /** 아직 bound 가 아니면(활성 enrollment 없음) 이 (venue, 라벨)로 bind. */
    public void bindIfUnbound(AvailabilityWindow window, String venueRefId, String sessionLabel) {
        if (window.getVenueRefId() == null) {
            window.setVenueRefId(venueRefId);
            window.setSessionLabel(sessionLabel);
        }
    }

    /** 활성(PENDING/CONFIRMED) enrollment 가 하나도 없으면 bind 해제(venueRefId/sessionLabel → null). */
    public void unbindIfEmpty(AvailabilityWindow window) {
        List<Enrollment> active = enrollmentRepo.findByAvailabilityWindowIdAndStatusIn(
                window.getId(), List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED));
        if (active.isEmpty()) {
            window.setVenueRefId(null);
            window.setSessionLabel(null);
        }
    }
}
