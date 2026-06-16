package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.VenueRefResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 수강신청 — 강사 측(받은 신청 목록 · 수락 · 거절). V2 enrollment-management 검토 시트의 BE.
 *
 * <p>게이트 = 강사신청 보유(availability/venue 와 동일 기조). 수락 시 정원 재검증(여러 PENDING 이 정원을
 * 넘게 쌓일 수 있으므로 — 넘으면 수락 거부, 강사가 거절로 정리). 거절은 복구 가능 + 활성 0 이면 window unbind.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorEnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefResolver venueRefResolver;
    private final WindowBinder windowBinder;

    public List<InstructorEnrollmentResponse> list(Account instructor, EnrollmentStatus status) {
        requireInstructorTrack(instructor);
        EnrollmentStatus s = status == null ? EnrollmentStatus.PENDING : status;
        List<Enrollment> list = enrollmentRepo
                .findByCourse_Instructor_IdAndStatusOrderByIdDesc(instructor.getId(), s);
        Map<String, String> names = resolveNames(list);
        return list.stream()
                .map(e -> InstructorEnrollmentResponse.of(e, names.get(e.getVenueRefId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public InstructorEnrollmentResponse accept(Account instructor, Long id) {
        Enrollment e = requireForInstructor(instructor, id);
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건만 수락
        }
        // 정원 재검증 — 확정 + 외부 hold 가 정원에 도달했으면 더 못 받음(거절로 정리)
        int confirmed = enrollmentRepo.countByAvailabilityWindowIdAndStatus(
                e.getAvailabilityWindow().getId(), EnrollmentStatus.CONFIRMED);
        if (confirmed + e.getAvailabilityWindow().heldCount() >= e.getAvailabilityWindow().effectiveCapacity()) {
            throw new BadRequestException(); // 정원 초과 — 수락 불가
        }
        e.setStatus(EnrollmentStatus.CONFIRMED);
        e.setRespondedAt(LocalDateTime.now());
        return InstructorEnrollmentResponse.of(e, venueName(e.getVenueRefId()));
    }

    @Transactional
    public InstructorEnrollmentResponse reject(Account instructor, Long id, String reason) {
        Enrollment e = requireForInstructor(instructor, id);
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건만 거절
        }
        e.setStatus(EnrollmentStatus.REJECTED);
        e.setRejectionReason(StringUtils.hasText(reason) ? reason.trim() : null);
        e.setRespondedAt(LocalDateTime.now());
        windowBinder.unbindIfEmpty(e.getAvailabilityWindow());
        return InstructorEnrollmentResponse.of(e, venueName(e.getVenueRefId()));
    }

    /* ─── helpers ─── */

    private void requireInstructorTrack(Account instructor) {
        if (!applicationRepo.existsByAccountId(instructor.getId())) {
            throw new BadRequestException();
        }
    }

    private Enrollment requireForInstructor(Account instructor, Long id) {
        Enrollment e = enrollmentRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (e.getCourse() == null || e.getCourse().getInstructor() == null
                || !e.getCourse().getInstructor().getId().equals(instructor.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 코스 신청 — 존재 숨김
        }
        return e;
    }

    private String venueName(String venueRefId) {
        if (!StringUtils.hasText(venueRefId)) {
            return null;
        }
        VenueRefResolver.Resolved r = venueRefResolver.resolveAll(List.of(venueRefId)).get(venueRefId);
        return r == null ? null : r.getName();
    }

    private Map<String, String> resolveNames(List<Enrollment> list) {
        List<String> refs = list.stream().map(Enrollment::getVenueRefId)
                .filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        if (refs.isEmpty()) {
            return Map.of();
        }
        return venueRefResolver.resolveAll(refs).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().getName()));
    }
}
