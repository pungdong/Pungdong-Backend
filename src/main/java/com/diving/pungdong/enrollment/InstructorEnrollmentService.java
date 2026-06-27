package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 수강신청 — 강사 측(받은 회차 · 수락 · 거절 · 일정변경요청). V2 enrollment-management 검토 시트의 BE. {@code {id}} = 회차 id.
 *
 * <p>게이트 = 강사신청 보유. 액션매트릭스: <b>1회차(진입)</b> = 수락/거절/일정변경요청, <b>진행 중(2회차+)</b> =
 * 수락/일정변경요청(거절 없음). 좌석은 신청 시점 lock 이라 수락은 슬롯 전환만(정원 재검증 없음). 일정변경요청 =
 * 같은 위치/이용권/블록으로 대안 날짜 제안 → 학생이 고르면 사전 수락(바로 결제 대기, docs/features/booking.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorEnrollmentService {

    private final EnrollmentRoundJpaRepo roundRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefResolver venueRefResolver;
    private final SessionCleaner sessionCleaner;
    private final BookableSlotDeriver slotDeriver;
    private final AvailabilityCoverageJpaRepo coverageRepo;

    public List<InstructorEnrollmentResponse> list(Account instructor, EnrollmentStatus status) {
        requireInstructorTrack(instructor);
        EnrollmentStatus s = status == null ? EnrollmentStatus.PENDING : status;
        List<EnrollmentRound> rounds = roundRepo
                .findByEnrollment_Course_Instructor_IdAndStatusOrderByIdDesc(instructor.getId(), s);
        Map<String, String> names = resolveNames(rounds);
        return rounds.stream()
                .map(r -> InstructorEnrollmentResponse.of(r, names.get(r.getVenueRefId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public InstructorEnrollmentResponse accept(Account instructor, Long roundId) {
        EnrollmentRound r = requireForInstructor(instructor, roundId);
        if (r.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건만 수락
        }
        // 좌석은 신청(PENDING) 시점에 이미 lock(선착순) — 수락은 그 슬롯을 결제 대기로 전환만(정원 재검증 불필요).
        r.setStatus(EnrollmentStatus.PAYMENT_PENDING);
        r.getProposedDates().clear(); // 혹시 남은 제안 정리
        r.setRespondedAt(LocalDateTime.now());
        return InstructorEnrollmentResponse.of(r, venueName(r.getVenueRefId()));
    }

    /** 거절 — <b>1회차(진입)만</b>. 진행 중 회차는 거절 대신 일정변경요청. 복구 가능(학생 재신청). */
    @Transactional
    public InstructorEnrollmentResponse reject(Account instructor, Long roundId, String reason) {
        EnrollmentRound r = requireForInstructor(instructor, roundId);
        if (r.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건만 거절
        }
        if (!r.isFirstMeeting()) {
            throw new BadRequestException(); // 거절은 1회차 한정 — 진행 중은 일정변경요청
        }
        AvailabilitySession session = r.getAvailabilitySession();
        r.setStatus(EnrollmentStatus.REJECTED);
        r.setRejectionReason(StringUtils.hasText(reason) ? reason.trim() : null);
        r.setRespondedAt(LocalDateTime.now());
        InstructorEnrollmentResponse resp = InstructorEnrollmentResponse.of(r, venueName(r.getVenueRefId()));
        sessionCleaner.deleteIfEmpty(session);
        return resp;
    }

    /**
     * 일정변경요청 — 같은 위치/이용권/블록으로 가능한 대안 날짜 제안. 각 날짜는 venue 운영블록 존재 + 강사
     * coverage 에 통째로 ⊆ 여야(정원은 학생 pick 시점에 재확인). 학생이 고르면 사전 수락 → 결제 대기.
     */
    @Transactional
    public InstructorEnrollmentResponse proposeDates(Account instructor, Long roundId, List<LocalDate> dates) {
        EnrollmentRound r = requireForInstructor(instructor, roundId);
        if (r.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건에만 일정변경요청
        }
        if (dates == null || dates.isEmpty()) {
            throw new BadRequestException();
        }
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(r.getVenueRefId())).get(r.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        List<LocalDate> valid = dates.stream().distinct()
                .filter(d -> bookableDate(instructor, venue, r, d))
                .collect(Collectors.toList());
        if (valid.isEmpty()) {
            throw new BadRequestException(); // 제안한 날짜 중 가능한 게 없음
        }
        r.getProposedDates().clear();
        r.getProposedDates().addAll(valid);
        r.setRespondedAt(LocalDateTime.now());
        return InstructorEnrollmentResponse.of(r, venueName(r.getVenueRefId()));
    }

    /* ─── helpers ─── */

    /** 그 날짜에 같은 위치/이용권/블록이 가능한가 — venue 운영블록 존재 + 강사 coverage 에 통째로 ⊆. */
    private boolean bookableDate(Account instructor, VenueResponse venue, EnrollmentRound r, LocalDate date) {
        boolean blockOk = slotDeriver.blocksFor(venue, r.getTicketRef(), date).stream()
                .anyMatch(b -> b.sameTime(r.getBlockStart(), r.getBlockEnd()));
        if (!blockOk) {
            return false;
        }
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(r.getBlockStart(), r.getBlockEnd()));
    }

    private void requireInstructorTrack(Account instructor) {
        if (!applicationRepo.existsByAccountId(instructor.getId())) {
            throw new BadRequestException();
        }
    }

    private EnrollmentRound requireForInstructor(Account instructor, Long roundId) {
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        var course = r.getEnrollment() == null ? null : r.getEnrollment().getCourse();
        if (course == null || course.getInstructor() == null
                || !course.getInstructor().getId().equals(instructor.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 코스 신청 — 존재 숨김
        }
        return r;
    }

    private String venueName(String venueRefId) {
        if (!StringUtils.hasText(venueRefId)) {
            return null;
        }
        VenueRefResolver.Resolved r = venueRefResolver.resolveAll(List.of(venueRefId)).get(venueRefId);
        return r == null ? null : r.getName();
    }

    private Map<String, String> resolveNames(List<EnrollmentRound> rounds) {
        List<String> refs = rounds.stream().map(EnrollmentRound::getVenueRefId)
                .filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        if (refs.isEmpty()) {
            return Map.of();
        }
        return venueRefResolver.resolveAll(refs).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().getName()));
    }
}
