package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.SessionCleaner;
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
 * 넘게 쌓일 수 있으므로 — 넘으면 수락 거부, 강사가 거절로 정리). 거절은 복구 가능.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorEnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefResolver venueRefResolver;
    private final SessionCleaner sessionCleaner;

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
        // 정원 재검증 — 점유(결제대기+확정) + 외부 hold 가 유효정원에 도달했으면 더 못 받음(거절로 정리)
        int occupying = enrollmentRepo.countByAvailabilitySessionIdAndStatusIn(
                e.getAvailabilitySession().getId(), EnrollmentStatus.OCCUPYING);
        if (occupying + e.getAvailabilitySession().heldCount() >= e.getAvailabilitySession().effectiveCapacity()) {
            throw new BadRequestException(); // 정원 초과 — 수락 불가
        }
        // 수락 = 결제 대기(슬롯 점유). 결제 승인 시 PaymentService 가 CONFIRMED 로 확정.
        e.setStatus(EnrollmentStatus.PAYMENT_PENDING);
        e.setRespondedAt(LocalDateTime.now());
        return InstructorEnrollmentResponse.of(e, venueName(e.getVenueRefId()));
    }

    @Transactional
    public InstructorEnrollmentResponse reject(Account instructor, Long id, String reason) {
        Enrollment e = requireForInstructor(instructor, id);
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건만 거절
        }
        AvailabilitySession session = e.getAvailabilitySession();
        e.setStatus(EnrollmentStatus.REJECTED);
        e.setRejectionReason(StringUtils.hasText(reason) ? reason.trim() : null);
        e.setRespondedAt(LocalDateTime.now());
        // 응답은 enrollment 스냅샷 기반(이력 보존). 그 일정이 비면 카드만 삭제(거절 이력은 남음).
        InstructorEnrollmentResponse resp = InstructorEnrollmentResponse.of(e, venueName(e.getVenueRefId()));
        sessionCleaner.deleteIfEmpty(session);
        return resp;
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
