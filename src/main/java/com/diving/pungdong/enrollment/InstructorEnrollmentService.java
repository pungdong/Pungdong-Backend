package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.enrollment.dto.ProposeSlotsRequest.SlotProposal;
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
    private final AvailabilitySessionJpaRepo sessionRepo;

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
        r.getProposedSlots().clear(); // 혹시 남은 제안 정리
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
     * 일정변경요청 — 위치 고정, 완전한 대안 슬롯(날짜+이용권+블록) 제안. 각 슬롯은 venue 운영블록 존재 + 강사
     * coverage 에 통째로 ⊆ 여야(정원은 학생 pick 시점에 재확인). 학생이 고르면 사전 수락 → 결제 대기.
     */
    @Transactional
    public InstructorEnrollmentResponse proposeSlots(Account instructor, Long roundId, List<SlotProposal> slots) {
        EnrollmentRound r = requireForInstructor(instructor, roundId);
        if (r.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 건에만 일정변경요청
        }
        if (slots == null || slots.isEmpty()) {
            throw new BadRequestException();
        }
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(r.getVenueRefId())).get(r.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        List<ProposedSlot> valid = slots.stream()
                .filter(s -> bookableSlot(instructor, venue, s))
                .map(s -> ProposedSlot.builder().date(s.getDate()).ticketRef(s.getTicketRef())
                        .blockStart(s.getBlockStart()).blockEnd(s.getBlockEnd()).build())
                .collect(Collectors.toList());
        if (valid.isEmpty()) {
            throw new BadRequestException(); // 제안한 슬롯 중 가능한 게 없음
        }
        r.getProposedSlots().clear();
        r.getProposedSlots().addAll(valid);
        r.setRespondedAt(LocalDateTime.now());
        return InstructorEnrollmentResponse.of(r, venueName(r.getVenueRefId()));
    }

    /**
     * 회차 완료(done) — 강사가 그 회차 수강을 마쳤다고 표시. CONFIRMED(결제 확정)만 완료 가능. 멱등(이미 done 이면 유지).
     * done = 다음 회차 게이트를 열고, 정산 대상이 된다(정산 연계는 후속).
     */
    @Transactional
    public InstructorEnrollmentResponse completeRound(Account instructor, Long roundId) {
        EnrollmentRound r = requireForInstructor(instructor, roundId);
        if (r.getStatus() != EnrollmentStatus.CONFIRMED) {
            throw new BadRequestException(); // 확정(결제 완료)된 회차만 완료 처리
        }
        if (r.getDoneAt() == null) {
            r.setDoneAt(LocalDateTime.now());
        }
        return InstructorEnrollmentResponse.of(r, venueName(r.getVenueRefId()));
    }

    /**
     * 일정(session) 통째 완료 — 그 세션의 모든 확정 회차(여러 수강생)를 일괄 done. 빠른 정산용. 완료 건수 반환.
     */
    @Transactional
    public int completeSession(Account instructor, Long sessionId) {
        AvailabilitySession session = sessionRepo.findById(sessionId).orElseThrow(ResourceNotFoundException::new);
        if (session.getInstructor() == null || !session.getInstructor().getId().equals(instructor.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 일정 — 존재 숨김
        }
        int done = 0;
        for (EnrollmentRound r : roundRepo.findByAvailabilitySessionIdAndStatusIn(
                sessionId, List.of(EnrollmentStatus.CONFIRMED))) {
            if (r.getDoneAt() == null) {
                r.setDoneAt(LocalDateTime.now());
                done++;
            }
        }
        return done;
    }

    /* ─── helpers ─── */

    /** 그 슬롯(날짜+이용권+블록)이 가능한가 — venue 운영블록 존재 + 강사 coverage 에 통째로 ⊆. (위치는 회차 고정.) */
    private boolean bookableSlot(Account instructor, VenueResponse venue, SlotProposal s) {
        if (s.getDate() == null || s.getTicketRef() == null || s.getBlockStart() == null || s.getBlockEnd() == null) {
            return false;
        }
        boolean blockOk = slotDeriver.blocksFor(venue, s.getTicketRef(), s.getDate()).stream()
                .anyMatch(b -> b.sameTime(s.getBlockStart(), s.getBlockEnd()));
        if (!blockOk) {
            return false;
        }
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), s.getDate()).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(s.getBlockStart(), s.getBlockEnd()));
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
