package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilityHold;
import com.diving.pungdong.availability.AvailabilityHoldJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse.EnrollmentCard;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse.FilterCount;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse.RoundCard;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse.SlotRef;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse.StudentSummary;
import com.diving.pungdong.enrollment.dto.ProposeSlotsRequest.SlotProposal;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 강사 일정변경 제안 슬롯 시스템 상한 — hold 를 거니 "한 회차가 잠그는 좌석 수" 상한(강사·학생 모두 3이 적정). */
    private static final int MAX_PROPOSED_SLOTS = 3;

    private final EnrollmentRoundJpaRepo roundRepo;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefResolver venueRefResolver;
    private final SessionCleaner sessionCleaner;
    private final BookableSlotDeriver slotDeriver;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final AvailabilityHoldJpaRepo holdRepo;
    private final SiteSettingsProvider siteSettings;

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

    /**
     * 강사 수강관리 hub — 거래 단위(수강생×강의) 카드 목록 + 필터 카운트. 학생 hub 의 강사 거울. 회차들에서
     * 강사 시점 상태/플래그/액션 한 줄을 파생(저장 X). {@code filter} = all|action|progress|completed.
     */
    public InstructorScheduleHubResponse hub(Account instructor, String filter) {
        requireInstructorTrack(instructor);
        LocalDate today = LocalDate.now();
        List<Enrollment> all = enrollmentRepo.findByCourse_Instructor_IdOrderByIdDesc(instructor.getId());

        // venue 이름 배치 해석
        List<String> refs = all.stream().flatMap(e -> e.getRounds().stream())
                .map(EnrollmentRound::getVenueRefId).filter(StringUtils::hasText).distinct()
                .collect(Collectors.toList());
        Map<String, String> venueNames = refs.isEmpty() ? Map.of()
                : venueRefResolver.resolveAll(refs).entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().getName()));

        // 학생별 "실제 수강(done 회차 보유)" 수강 수 — 로드된 집합에서 in-memory (이 강사와의 이력)
        Map<Long, Long> attendedByStudent = all.stream()
                .filter(e -> e.getStudent() != null && hasDoneRound(e))
                .collect(Collectors.groupingBy(e -> e.getStudent().getId(), Collectors.counting()));

        List<EnrollmentCard> cards = all.stream()
                .map(e -> card(e, today, venueNames, attendedByStudent))
                .collect(Collectors.toList());

        List<FilterCount> filters = List.of(
                new FilterCount("all", "전체", cards.size()),
                new FilterCount("action", "액션 필요", count(cards, InstructorEnrollmentStatus.ACTION_NEEDED)),
                new FilterCount("progress", "진행중", count(cards, InstructorEnrollmentStatus.PROGRESS)),
                new FilterCount("completed", "완료", count(cards, InstructorEnrollmentStatus.COMPLETED)));

        List<EnrollmentCard> filtered = cards.stream()
                .filter(c -> matchesFilter(c, filter))
                .sorted(Comparator.comparingInt(c -> c.getStatus().ordinal())) // 액션필요(0)→진행중→완료→취소
                .collect(Collectors.toList());

        return new InstructorScheduleHubResponse(filters, filtered);
    }

    private EnrollmentCard card(Enrollment e, LocalDate today, Map<String, String> venueNames,
                                Map<Long, Long> attendedByStudent) {
        Course course = e.getCourse();
        int totalRegular = course == null ? 0 : (int) course.getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR).count();

        // 회차 정렬: 정규(roundIndex) → EXTRA(뒤). 파생용 전체 상태 + 표시용(취소 제외) 분리.
        List<EnrollmentRound> sorted = e.getRounds().stream()
                .sorted(Comparator.comparing((EnrollmentRound r) -> r.getRoundKind() == RoundKind.EXTRA)
                        .thenComparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex())
                        .thenComparing(EnrollmentRound::getId))
                .collect(Collectors.toList());
        List<InstructorRoundStatus> allStatuses = sorted.stream()
                .map(r -> InstructorRoundStatus.from(r, today)).collect(Collectors.toList());
        int doneRegular = (int) sorted.stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.isDone()).count();

        InstructorEnrollmentStatus status = InstructorEnrollmentStatus.derive(allStatuses, totalRegular, doneRegular);
        InstructorActionFlag flag = InstructorActionFlag.derive(allStatuses);

        List<RoundCard> roundCards = new ArrayList<>();
        for (EnrollmentRound r : sorted) {
            InstructorRoundStatus rs = InstructorRoundStatus.from(r, today);
            if (rs == InstructorRoundStatus.CANCELLED) {
                continue; // 죽은 회차는 카드에서 숨김(파생엔 반영됨)
            }
            roundCards.add(roundCard(r, rs, venueNames));
        }

        long attended = e.getStudent() == null ? 0 : attendedByStudent.getOrDefault(e.getStudent().getId(), 0L);
        long history = attended - (hasDoneRound(e) ? 1 : 0); // 현재 수강 제외
        StudentSummary student = e.getStudent() == null ? null : StudentSummary.builder()
                .accountId(e.getStudent().getId())
                .name(e.getStudent().getNickName())
                .initials(initials(e.getStudent().getNickName()))
                .isNew(history <= 0)
                .historyCount((int) Math.max(0, history))
                .build();

        return EnrollmentCard.builder()
                .enrollmentId(e.getId())
                .student(student)
                .courseId(course == null ? null : course.getId())
                .courseTitle(course == null ? null : course.getTitle())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(course == null ? List.<CertLevel>of() : new ArrayList<>(course.getLevels()))
                .status(status)
                .flag(flag)
                .actionLine(actionLine(flag, student, sorted, today))
                .totalRounds(totalRegular)
                .rounds(roundCards)
                .build();
    }

    private RoundCard roundCard(EnrollmentRound r, InstructorRoundStatus rs, Map<String, String> venueNames) {
        SlotRef prev = null;
        if (rs == InstructorRoundStatus.CHANGING && !r.getSlotHistory().isEmpty()) {
            PastSlot p = r.getSlotHistory().get(r.getSlotHistory().size() - 1); // 직전 슬롯
            prev = SlotRef.builder().date(p.getDate()).venueRefId(p.getVenueRefId()).ticketRef(p.getTicketRef())
                    .blockStart(p.getBlockStart()).blockEnd(p.getBlockEnd()).build();
        }
        return RoundCard.builder()
                .roundId(r.getId())
                .roundIndex(r.getRoundIndex())
                .roundKind(r.getRoundKind() == null ? null : r.getRoundKind().name())
                .status(rs)
                .date(r.getDate())
                .blockStart(r.getBlockStart())
                .blockEnd(r.getBlockEnd())
                .venueRefId(r.getVenueRefId())
                .venueName(venueNames.get(r.getVenueRefId()))
                .amount(r.chargeTotal())
                .gearCount(r.getEquipment().size())
                .gearItems(r.getEquipment().stream()
                        .map(e -> com.diving.pungdong.enrollment.dto.GearItem.builder()
                                .name(e.getName())
                                .sizeLabel(e.getSize())
                                .build())
                        .collect(Collectors.toList()))
                .previousSlot(prev)
                .build();
    }

    /** 액션 안내 한 줄 — flag + 학생/회차 기반. 없으면 null. */
    private String actionLine(InstructorActionFlag flag, StudentSummary student, List<EnrollmentRound> rounds,
                              LocalDate today) {
        if (flag == null) {
            return null;
        }
        String who = student == null ? "학생" : student.getName();
        switch (flag) {
            case NEW_REQUEST:
                return who + " 학생의 신규 신청 · 응답해주세요";
            case CHANGE_REQUEST:
                return actionRoundLabel(rounds, today, InstructorRoundStatus.CHANGING) + " 일정 변경 요청 · 검토해주세요";
            case CLOSING:
                return actionRoundLabel(rounds, today, InstructorRoundStatus.CLOSING) + " 종료 · 세션을 마무리해주세요";
            default:
                return null;
        }
    }

    private String actionRoundLabel(List<EnrollmentRound> rounds, LocalDate today, InstructorRoundStatus target) {
        return rounds.stream()
                .filter(r -> InstructorRoundStatus.from(r, today) == target)
                .map(r -> r.getRoundKind() == RoundKind.EXTRA ? "추가세션"
                        : (r.getRoundIndex() == null ? "회차" : r.getRoundIndex() + "회차"))
                .findFirst().orElse("회차");
    }

    private boolean hasDoneRound(Enrollment e) {
        return e.getRounds().stream().anyMatch(EnrollmentRound::isDone);
    }

    private boolean matchesFilter(EnrollmentCard c, String filter) {
        if (filter == null || filter.isBlank() || "all".equals(filter)) {
            return true;
        }
        switch (filter) {
            case "action":
                return c.getStatus() == InstructorEnrollmentStatus.ACTION_NEEDED;
            case "progress":
                return c.getStatus() == InstructorEnrollmentStatus.ACTION_NEEDED
                        || c.getStatus() == InstructorEnrollmentStatus.PROGRESS;
            case "completed":
                return c.getStatus() == InstructorEnrollmentStatus.COMPLETED;
            default:
                return true;
        }
    }

    private int count(List<EnrollmentCard> cards, InstructorEnrollmentStatus status) {
        return (int) cards.stream().filter(c -> c.getStatus() == status).count();
    }

    private String initials(String name) {
        return StringUtils.hasText(name) ? name.trim().substring(0, 1) : "·";
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
     * 일정변경요청 — 위치 고정, 완전한 대안 슬롯(날짜+이용권+블록) 제안(최대 {@value #MAX_PROPOSED_SLOTS}개). 각
     * 슬롯은 venue 운영블록 존재 + 강사 coverage 에 통째로 ⊆ + <b>좌석 여유</b>여야 채택된다. <b>하드캡 보장</b>:
     * 채택된 슬롯마다 그 일정에 좌석 hold(회차 귀속, proposalTtlHours 만료)를 잡아 — 학생이 고를 때 반드시 잡히게
     * 하고(만석으로 막히는 아이러니 제거), 그 동안 다른 학생 신청은 정상적으로 막힌다(heldCount 합산). 만석/불가
     * 슬롯은 조용히 제외(전부 제외면 400). 학생이 고르면 사전 수락 → 결제 대기.
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
        if (slots.size() > MAX_PROPOSED_SLOTS) {
            throw new BadRequestException(); // 제안은 최대 3개(한 회차가 잠그는 좌석 상한)
        }
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(r.getVenueRefId())).get(r.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        releaseProposalHolds(r); // 재제안 — 이전 보장 hold 회수 후 다시 잡음

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(siteSettings.current().proposalTtlHours());
        List<ProposedSlot> valid = new ArrayList<>();
        Set<Long> heldSessionIds = new HashSet<>();
        for (SlotProposal s : slots) {
            if (isCurrentSlot(r, s) || !bookableSlot(instructor, venue, s)) {
                continue; // 현재 슬롯과 동일(변경 아님) 또는 기하 불가(운영블록 없음/coverage 밖) — 제외
            }
            AvailabilitySession session = findOrCreateSession(instructor, s.getDate(),
                    s.getBlockStart(), s.getBlockEnd(), r.getVenueRefId(), s.getTicketRef());
            if (heldSessionIds.contains(session.getId())) {
                valid.add(toProposedSlot(s)); // 같은 (위치,블록) 다른 이용권 — hold 는 한 번만(좌석 하나)
                continue;
            }
            if (!hasSeat(session)) {
                sessionCleaner.deleteIfEmpty(session); // 만석 — 제외(방금 만든 빈 일정이면 정리)
                continue;
            }
            session.addHold(AvailabilityHold.builder()
                    .count(1).proposalRoundId(r.getId()).expiresAt(expiresAt).createdAt(now).build());
            heldSessionIds.add(session.getId());
            valid.add(toProposedSlot(s));
        }
        if (valid.isEmpty()) {
            throw new BadRequestException(); // 제안한 슬롯 중 가능한 게 없음(전부 만석/불가)
        }
        r.getProposedSlots().clear();
        r.getProposedSlots().addAll(valid);
        r.setRespondedAt(now);
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

    /** 제안 슬롯이 회차의 현재 슬롯과 동일한가 — 위치 고정이므로 (날짜,이용권,블록)만 비교. 동일하면 변경이 아님. */
    private static boolean isCurrentSlot(EnrollmentRound r, SlotProposal s) {
        return Objects.equals(r.getDate(), s.getDate())
                && Objects.equals(r.getTicketRef(), s.getTicketRef())
                && Objects.equals(r.getBlockStart(), s.getBlockStart())
                && Objects.equals(r.getBlockEnd(), s.getBlockEnd());
    }

    private static ProposedSlot toProposedSlot(SlotProposal s) {
        return ProposedSlot.builder().date(s.getDate()).ticketRef(s.getTicketRef())
                .blockStart(s.getBlockStart()).blockEnd(s.getBlockEnd()).build();
    }

    /** 만석? — 활성(대기+결제대기+확정) + 외부/제안 hold 가 유효정원 미만이면 좌석 있음(하드캡). */
    private boolean hasSeat(AvailabilitySession session) {
        int occupied = roundRepo.countByAvailabilitySessionIdAndStatusIn(session.getId(), EnrollmentStatus.ACTIVE);
        return occupied + session.heldCount() < session.effectiveCapacity();
    }

    /** (위치,블록) 일정을 찾거나 없으면 생성 — 정체성은 (instructor,date,start,end,venueRef). ticketRef 는 표시 대표값. */
    private AvailabilitySession findOrCreateSession(Account instructor, LocalDate date,
                                                    LocalTime start, LocalTime end, String venueRef, String ticketRef) {
        return sessionRepo.findByInstructorIdAndDateAndStartTimeAndEndTime(instructor.getId(), date, start, end)
                .stream().filter(s -> Objects.equals(s.getVenueRefId(), venueRef)).findFirst()
                .orElseGet(() -> sessionRepo.save(AvailabilitySession.builder()
                        .instructor(instructor).date(date).startTime(start).endTime(end)
                        .venueRefId(venueRef).ticketRef(ticketRef)
                        .createdAt(LocalDateTime.now()).build()));
    }

    /** 이 회차의 강사 제안 보장 hold 를 모두 해제(세션 컬렉션에서 제거 → orphanRemoval) + 빈 일정 정리. */
    private void releaseProposalHolds(EnrollmentRound round) {
        List<AvailabilityHold> holds = holdRepo.findByProposalRoundId(round.getId());
        if (holds.isEmpty()) {
            return;
        }
        List<AvailabilitySession> touched = new ArrayList<>();
        for (AvailabilityHold h : holds) {
            AvailabilitySession s = h.getSession();
            if (s != null) {
                s.getHolds().remove(h);
                touched.add(s);
            }
        }
        touched.forEach(sessionCleaner::deleteIfEmpty);
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
