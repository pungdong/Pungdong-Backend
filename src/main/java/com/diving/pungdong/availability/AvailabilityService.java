package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.availability.dto.ApplicantSummaryResponse;
import com.diving.pungdong.availability.dto.AvailabilityCalendarResponse;
import com.diving.pungdong.availability.dto.AvailabilitySessionResponse;
import com.diving.pungdong.availability.dto.AvailabilitySettingsResponse;
import com.diving.pungdong.availability.dto.CoverageRangeResponse;
import com.diving.pungdong.availability.dto.CoverageRequest;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.availability.dto.SessionCreateRequest;
import com.diving.pungdong.availability.dto.TimeRangeRequest;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.CoverageHasSessionException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.VenueRefValidator;
import com.diving.pungdong.venue.dto.VenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강사 캘린더 — 두 레이어. <b>coverage(예약가능시간)</b> = 순수 시간 띠({@link AvailabilityCoverage}, 머지
 * 정규화), <b>session(일정)</b> = 위치·정원·점유({@link AvailabilitySession}). 결합은 시간 포함 판정뿐.
 *
 * <p>핵심: ① 일정 원자 추가({@link #addSession}) = coverage 확장+머지 후 session 생성/join 을 1 트랜잭션에.
 * ② coverage 직접 편집(open/close)도 항상 머지, 닫기가 session 가로지르면 거부({@link CoverageHasSessionException}).
 * ③ 정원 = 계정 기본값 + session override(PR #69). 게이트 = 강사신청 보유.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvailabilityService {

    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefValidator venueRefValidator;
    private final VenueRefResolver venueRefResolver;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final SessionCleaner sessionCleaner;
    // 슬롯 상세의 "세션 단체 채팅" CTA 상태. chat 쪽은 레포만 참조하므로 순환 없음.
    private final com.diving.pungdong.chat.ChatQueryService chatQueryService;
    private final SessionOverlapGuard overlapGuard;

    /** 열기 한 번에 받을 시간 구간 수 상한. 하루를 쪼개는 현실적인 한도(오전/오후/야간 …)를 넉넉히 덮는다. */
    private static final int MAX_TIME_RANGES = 10;

    /* ─── coverage(예약가능시간) 직접 편집 ───────────────────── */

    /**
     * 예약가능시간 열기(union) — recurrence 전개 × 시간 구간들, 각 날 머지. 영향 받은 날들의 머지된 구간 반환.
     *
     * <p><b>전개 결과가 0일이면 200 + 빈 배열</b>(예외 아님). "선택한 요일이 이 기간에 안 남았다" 는 서버가
     * 정상적으로 계산한 답이지 잘못된 요청이 아니다. 예전엔 여기서 {@link BadRequestException} 을 던졌는데,
     * 그러면 시간 형식 오류와 <b>글자까지 똑같은</b> {@code -1011} 이 나가 클라이언트가 둘을 구분할 수 없었다.
     */
    @Transactional
    public List<CoverageRangeResponse> openCoverage(Account instructor, CoverageRequest req) {
        requireInstructorTrack(instructor);
        List<Span> spans = resolveOpenSpans(req);
        Set<LocalDate> dates = expandDates(req);
        if (dates.isEmpty()) {
            return List.of();
        }
        for (LocalDate d : dates) {
            // 그 날 기존 구간 + 요청 구간들을 한 번에 정규화하고 한 번만 교체한다.
            // 구간마다 ensureCoverage 를 부르면 (날짜 × 구간) 만큼 그 날 row 전체 delete+insert 가 반복된다.
            List<Span> all = new ArrayList<>(loadSpans(instructor, d));
            all.addAll(spans);
            replaceCoverage(instructor, d, CoverageMerger.normalize(all));
        }
        LocalDate from = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate to = dates.stream().max(LocalDate::compareTo).orElseThrow();
        return coverageRanges(instructor, from, to);
    }

    /** 예약가능시간 닫기(subtract, 단일 날) — 그 구간에 일정이 걸치면 거부. 그 날 머지된 구간 반환. */
    @Transactional
    public List<CoverageRangeResponse> closeCoverage(Account instructor, CoverageRequest req) {
        requireInstructorTrack(instructor);
        req.setEndTime(DayEnd.normalizeEnd(req.getEndTime())); // 하루 끝(23:59~) 은 23:59:59 로 수렴(정규화 후 검증)
        requireValidRange(req.getStartTime(), req.getEndTime());
        Span cut = new Span(req.getStartTime(), req.getEndTime());
        if (sessionOverlaps(instructor, req.getDate(), cut)) {
            throw new CoverageHasSessionException();
        }
        List<Span> result = CoverageMerger.subtract(loadSpans(instructor, req.getDate()), cut);
        replaceCoverage(instructor, req.getDate(), result);
        return coverageRanges(instructor, req.getDate(), req.getDate());
    }

    /* ─── session(일정) 원자 추가 + 관리 ─────────────────────── */

    /**
     * 일정 원자 추가 — coverage 확장+머지 → (위치,시간) session 찾거나 생성 → count&gt;0 이면 점유 기록.
     * 한 트랜잭션. 외부 점유는 유효정원 넘겨도 기록(자동확장 없음).
     */
    @Transactional
    public AvailabilitySessionResponse addSession(Account instructor, SessionCreateRequest req) {
        requireInstructorTrack(instructor);
        req.setEndTime(DayEnd.normalizeEnd(req.getEndTime())); // 하루 끝(23:59~) 은 23:59:59 로 수렴(정규화 후 검증)
        requireValidRange(req.getStartTime(), req.getEndTime());
        requireValidOverride(req.getCapacity());
        if (req.getCount() == null || req.getCount() < 1) {
            throw new BadRequestException(); // 일정 = 점유 추가. 점유 없이 시간만 열려면 POST /coverage.
        }
        String venueRef = trimToNull(req.getVenueRefId());
        String ticketRef = trimToNull(req.getTicketRef());
        if (ticketRef != null && venueRef == null) {
            throw new BadRequestException(); // 이용권은 위치에 속한다 — venueRefId 없이 ticketRef 불가
        }
        if (venueRef != null) {
            venueRefValidator.validate(instructor, venueRef);
            if (ticketRef != null) {
                requireTicketInVenue(venueRef, ticketRef);
            }
        }
        // 기존 일정과 시간 겹치면 거부(한 강사 = 한 번에 한 세션). 정확히 같은 (위치,시간)은 join 이라 통과.
        overlapGuard.requireNoOverlap(instructor.getId(), req.getDate(), venueRef, req.getStartTime(), req.getEndTime());
        // ① coverage 가 이 시간대를 덮도록 보장(없으면 확장 + 머지)
        ensureCoverage(instructor, req.getDate(), new Span(req.getStartTime(), req.getEndTime()));

        // ② (위치,이용권,시간) session 찾거나 생성
        AvailabilitySession session = findOrCreateSession(
                instructor, req.getDate(), req.getStartTime(), req.getEndTime(),
                venueRef, ticketRef, req.getCapacity());

        // ③ 점유 기록 + 점유가 정원 넘으면 커스텀 정원으로 확장(강사가 그만큼 받겠다는 선언)
        session.addHold(AvailabilityHold.builder()
                .count(req.getCount())
                .memo(trimToNull(req.getMemo()))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
        bumpCapacityIfExceeded(session);
        session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(session);
    }

    /** 일정 삭제 — 활성(대기/결제대기/확정) 신청이 있으면 거부(확정 취소 없음). coverage 는 그대로(독립). */
    @Transactional
    public void deleteSession(Account instructor, Long id) {
        AvailabilitySession s = requireOwned(instructor, id);
        boolean hasActive = !roundRepo.findByAvailabilitySessionIdAndStatusIn(
                id, EnrollmentStatus.ACTIVE).isEmpty();
        if (hasActive) {
            throw new BadRequestException(); // 신청이 있는 일정은 못 지움
        }
        sessionRepo.delete(s);
    }

    /** 일정 정원 override 설정/변경 — 1 이상. 점유보다 낮춰도 허용(확정 바닥). */
    @Transactional
    public AvailabilitySessionResponse setSessionCapacity(Account instructor, Long id, Integer capacity) {
        AvailabilitySession s = requireOwned(instructor, id);
        requirePositive(capacity);
        s.setCapacityOverride(capacity);
        s.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(s);
    }

    /** 일정 override 해제 — 계정 기본값을 라이브로 따른다. */
    @Transactional
    public AvailabilitySessionResponse resetSessionCapacity(Account instructor, Long id) {
        AvailabilitySession s = requireOwned(instructor, id);
        s.setCapacityOverride(null);
        s.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(s);
    }

    /** 기존 일정에 점유 추가(±/외부). 유효정원 넘겨도 기록. */
    @Transactional
    public AvailabilitySessionResponse addHold(Account instructor, Long id, HoldRequest req) {
        AvailabilitySession s = requireOwned(instructor, id);
        if (req.getCount() < 1) {
            throw new BadRequestException();
        }
        s.addHold(AvailabilityHold.builder()
                .count(req.getCount())
                .memo(trimToNull(req.getMemo()))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
        bumpCapacityIfExceeded(s);
        s.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(s);
    }

    /**
     * 점유(확정 + 외부 hold)가 유효정원을 넘으면 그 session 을 <b>커스텀 정원(=점유)</b>으로 확장 — 강사가
     * 점유를 추가할 때만(외부예약/±). "그만큼 받겠다"는 선언이라 6명 넣으면 6/6. (정원을 의도적으로 *낮추는*
     * 건 확장 안 하고 바닥 유지 — 확정자 보호, over 표시·새 수락 차단. 학생 신청은 만석 캡이라 여기 안 옴.)
     */
    private void bumpCapacityIfExceeded(AvailabilitySession s) {
        int occupancy = s.heldCount()
                + roundRepo.countByAvailabilitySessionIdAndStatusIn(s.getId(), EnrollmentStatus.ACTIVE);
        if (occupancy > s.effectiveCapacity()) {
            s.setCapacityOverride(occupancy);
        }
    }

    /** 점유 제거(± −1 / 외부예약 취소). 제거 후 점유 0 이면 빈 일정 삭제 → Optional.empty(컨트롤러 204). */
    @Transactional
    public Optional<AvailabilitySessionResponse> removeHold(Account instructor, Long id, Long holdId) {
        AvailabilitySession s = requireOwned(instructor, id);
        boolean removed = s.getHolds().removeIf(h -> h.getId().equals(holdId));
        if (!removed) {
            throw new ResourceNotFoundException();
        }
        s.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (sessionCleaner.deleteIfEmpty(s)) {
            return Optional.empty();
        }
        return Optional.of(toResponse(s));
    }

    /* ─── 정원 — 계정 기본값(baseline) ──────────────────────── */

    public AvailabilitySettingsResponse getSettings(Account instructor) {
        requireInstructorTrack(instructor);
        return AvailabilitySettingsResponse.builder()
                .defaultCapacity(instructor.effectiveDefaultCapacity()).build();
    }

    @Transactional
    public AvailabilitySettingsResponse updateDefaultCapacity(Account instructor, Integer capacity) {
        requireInstructorTrack(instructor);
        requirePositive(capacity);
        instructor.setDefaultCapacity(capacity);
        accountRepo.save(instructor);
        return AvailabilitySettingsResponse.builder().defaultCapacity(capacity).build();
    }

    /* ─── 조회 ─────────────────────────────────────────────── */

    /** 캘린더 [from,to] — coverage 구간[] + session[] 분리. */
    public AvailabilityCalendarResponse getCalendar(Account instructor, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException();
        }
        List<AvailabilitySession> sessions =
                sessionRepo.findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(instructor.getId(), from, to);
        Map<String, VenueResponse> venueByRef = resolveVenues(sessions);
        Map<Long, List<EnrollmentRound>> activeBySession = activeBySession(
                sessions.stream().map(AvailabilitySession::getId).collect(Collectors.toList()));
        Map<Long, com.diving.pungdong.chat.dto.RoundChatState> chatStates = chatStatesFor(sessions);
        return AvailabilityCalendarResponse.builder()
                .coverage(coverageRanges(instructor, from, to))
                .sessions(sessions.stream()
                        .map(s -> toResponse(s, venueByRef, activeBySession, chatStates))
                        .collect(Collectors.toList()))
                .build();
    }

    public AvailabilitySessionResponse getSession(Account instructor, Long id) {
        return toResponse(requireOwned(instructor, id));
    }

    /* ─── coverage 내부 ─────────────────────────────────────── */

    /** 그 시간대가 coverage 에 들어오도록 union + 머지 + 교체. */
    private void ensureCoverage(Account instructor, LocalDate date, Span span) {
        List<Span> merged = CoverageMerger.union(loadSpans(instructor, date), span);
        replaceCoverage(instructor, date, merged);
    }

    private List<Span> loadSpans(Account instructor, LocalDate date) {
        return coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime()))
                .collect(Collectors.toList());
    }

    /** 그 날 coverage row 를 newSpans 로 통째 교체(머지 결과 박제). */
    private void replaceCoverage(Account instructor, LocalDate date, List<Span> newSpans) {
        coverageRepo.deleteAll(coverageRepo.findByInstructorIdAndDate(instructor.getId(), date));
        for (Span s : newSpans) {
            coverageRepo.save(AvailabilityCoverage.builder()
                    .instructor(instructor).date(date).startTime(s.start()).endTime(s.end()).build());
        }
    }

    private List<CoverageRangeResponse> coverageRanges(Account instructor, LocalDate from, LocalDate to) {
        return coverageRepo.findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(instructor.getId(), from, to)
                .stream().map(CoverageRangeResponse::of).collect(Collectors.toList());
    }

    private boolean sessionOverlaps(Account instructor, LocalDate date, Span cut) {
        return sessionRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .anyMatch(s -> new Span(s.getStartTime(), s.getEndTime()).overlaps(cut));
    }

    /* ─── session 내부 ──────────────────────────────────────── */

    private AvailabilitySession findOrCreateSession(Account instructor, LocalDate date, LocalTime start,
                                                    LocalTime end, String venueRef, String ticketRef,
                                                    Integer capacityOverride) {
        // 정체성 = (위치,시간). ticketRef 는 정체성 아님(표시 대표값) — 정원은 물리 슬롯 단위로 공유.
        return sessionRepo.findByInstructorIdAndDateAndStartTimeAndEndTime(instructor.getId(), date, start, end)
                .stream().filter(s -> Objects.equals(s.getVenueRefId(), venueRef)).findFirst()
                .orElseGet(() -> sessionRepo.save(AvailabilitySession.builder()
                        .instructor(instructor).date(date).startTime(start).endTime(end)
                        .venueRefId(venueRef).ticketRef(ticketRef)
                        .capacityOverride(capacityOverride)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build()));
    }

    /** ticketRef 가 그 venue 의 이용권인지 — 아니면 400(bogus ref 저장 방지). */
    private void requireTicketInVenue(String venueRef, String ticketRef) {
        VenueResponse vr = venueRefResolver.resolveVenues(List.of(venueRef)).get(venueRef);
        boolean ok = vr != null && vr.getTickets() != null
                && vr.getTickets().stream().anyMatch(t -> ticketRef.equals(t.getTicketRef()));
        if (!ok) {
            throw new BadRequestException();
        }
    }

    /* ─── 상태 파생 + 매핑 ──────────────────────────────────── */

    SlotStatus deriveStatus(AvailabilitySession s, int confirmed, int pending) {
        int external = s.heldCount();
        int occupied = confirmed + pending + external; // 신청 시점 좌석 lock — PENDING 도 자리를 차지
        if (occupied == 0) {
            return SlotStatus.AVAILABLE;
        }
        if (occupied >= s.effectiveCapacity()) {
            return SlotStatus.FULL;
        }
        if (confirmed + external == 0) {
            return SlotStatus.PENDING; // 대기만 — 아직 확정/외부 점유 없음
        }
        if (external > 0) {
            return SlotStatus.EXTERNAL;
        }
        return SlotStatus.CONFIRMED;
    }

    private AvailabilitySessionResponse toResponse(AvailabilitySession s) {
        return toResponse(s, resolveVenues(List.of(s)), activeBySession(List.of(s.getId())),
                chatStatesFor(List.of(s)));
    }

    /**
     * 슬롯 상세·캘린더의 "세션 단체 채팅" CTA 상태를 한 번에 푼다.
     *
     * <p>이 도메인의 조회자는 언제나 <b>슬롯 소유자(강사)</b>다({@code requireOwned} 를 통과한 뒤에만 매핑한다)
     * — 그래서 viewer 를 따로 받지 않고 세션의 강사를 쓴다. 세션마다 조회하면 캘린더가 N+1 이 되므로
     * 배치 변형만 둔다.
     */
    private Map<Long, com.diving.pungdong.chat.dto.RoundChatState> chatStatesFor(
            List<AvailabilitySession> sessions) {
        Long viewerId = sessions.stream()
                .map(AvailabilitySession::getInstructor).filter(java.util.Objects::nonNull)
                .map(Account::getId).findFirst().orElse(null);
        if (viewerId == null) {
            return Map.of();
        }
        return chatQueryService.statesFor(viewerId,
                sessions.stream().map(AvailabilitySession::getId).collect(Collectors.toList()));
    }

    private AvailabilitySessionResponse toResponse(AvailabilitySession s, Map<String, VenueResponse> venueByRef,
                                                   Map<Long, List<EnrollmentRound>> activeBySession,
                                                   Map<Long, com.diving.pungdong.chat.dto.RoundChatState> chatStates) {
        List<EnrollmentRound> active = activeBySession.getOrDefault(s.getId(), List.of());
        // 점유(결제대기+확정)는 confirmed 버킷으로 합산 — 둘 다 좌석을 차지(v1; FE 별도 표시는 후속).
        int confirmed = (int) active.stream().filter(e -> e.getStatus().occupiesCapacity()).count();
        int pending = (int) active.stream().filter(e -> e.getStatus() == EnrollmentStatus.PENDING).count();
        int external = s.heldCount();
        SlotStatus status = deriveStatus(s, confirmed, pending);
        VenueResponse vr = s.getVenueRefId() == null ? null : venueByRef.get(s.getVenueRefId());
        String venueName = vr == null ? null : vr.getName();
        String ticketName = ticketName(vr, s.getTicketRef());
        List<ApplicantSummaryResponse> applicants = active.stream()
                .map(AvailabilityService::toApplicant).collect(Collectors.toList());
        // filled = 찬 자리 = 확정 + 대기 + 외부 (신청 시점 좌석 lock — PENDING 도 점유)
        AvailabilitySessionResponse response = AvailabilitySessionResponse.of(
                s, status, confirmed + pending + external, confirmed, external, pending, venueName, ticketName, applicants);
        // 채팅 CTA 는 of(...) 시그니처를 늘리지 않고 여기서 붙인다(호출부가 여럿이라 파라미터를 더 늘리면 읽기 어렵다).
        response.setChat(chatStates.getOrDefault(s.getId(),
                com.diving.pungdong.chat.dto.RoundChatState.hidden()));
        return response;
    }

    /** ticketRef → venue 이용권 명칭(단일 출처). 미지정/미존재면 null. */
    private static String ticketName(VenueResponse vr, String ticketRef) {
        if (vr == null || ticketRef == null || vr.getTickets() == null) {
            return null;
        }
        return vr.getTickets().stream().filter(t -> ticketRef.equals(t.getTicketRef()))
                .map(VenueResponse.Ticket::getName).findFirst().orElse(null);
    }

    private Map<Long, List<EnrollmentRound>> activeBySession(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return roundRepo.findByAvailabilitySessionIdInAndStatusIn(
                        sessionIds, EnrollmentStatus.ACTIVE)
                .stream().collect(Collectors.groupingBy(r -> r.getAvailabilitySession().getId()));
    }

    /** 회차 → 슬롯 안 학생 요약. 단체·레벨은 평탄 3종(FE 가 Sanity 로 표시명 해석 — [[sanity-read-principle]]). */
    private static ApplicantSummaryResponse toApplicant(EnrollmentRound r) {
        var enrollment = r.getEnrollment();
        var course = enrollment == null ? null : enrollment.getCourse();
        var student = enrollment == null ? null : enrollment.getStudent();
        List<String> levels = course == null || course.getLevels() == null ? List.of()
                : course.getLevels().stream().sorted().map(Enum::name).collect(Collectors.toList());
        return ApplicantSummaryResponse.builder()
                .name(student == null ? null : student.getNickName())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(levels)
                .gear(r.getEquipment().stream()
                        .map(eq -> com.diving.pungdong.enrollment.dto.GearItem.builder()
                                .name(eq.getName()).sizeLabel(eq.getSize()).build())
                        .collect(Collectors.toList()))
                .kind(null)
                .build();
    }

    private Map<String, VenueResponse> resolveVenues(List<AvailabilitySession> sessions) {
        Set<String> refs = sessions.stream().map(AvailabilitySession::getVenueRefId)
                .filter(StringUtils::hasText).collect(Collectors.toCollection(LinkedHashSet::new));
        return refs.isEmpty() ? Map.of() : venueRefResolver.resolveVenues(refs);
    }

    /* ─── 게이트 + 검증 + 전개 ──────────────────────────────── */

    private void requireInstructorTrack(Account instructor) {
        if (!applicationRepo.existsByAccountId(instructor.getId())) {
            throw new BadRequestException();
        }
    }

    private void requireValidRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BadRequestException();
        }
    }

    private void requirePositive(Integer capacity) {
        if (capacity == null || capacity < 1) {
            throw new BadRequestException();
        }
    }

    private void requireValidOverride(Integer capacity) {
        if (capacity != null && capacity < 1) {
            throw new BadRequestException();
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    /**
     * 열기 시간 구간 결정 — {@code timeRanges} 가 비어있지 않으면 그것만, 아니면 {@code startTime/endTime} 한 벌.
     *
     * <p>각 구간은 {@link DayEnd#normalizeEnd} 로 하루 끝(23:59~)을 수렴시킨 뒤 검증한다. 하나라도 잘못되면
     * 전체 400 — 부분 반영은 없다.
     */
    private List<Span> resolveOpenSpans(CoverageRequest req) {
        List<TimeRangeRequest> ranges = req.getTimeRanges();
        if (ranges == null || ranges.isEmpty()) {
            req.setEndTime(DayEnd.normalizeEnd(req.getEndTime()));
            requireValidRange(req.getStartTime(), req.getEndTime());
            return List.of(new Span(req.getStartTime(), req.getEndTime()));
        }
        if (ranges.size() > MAX_TIME_RANGES) {
            throw new BadRequestException();
        }
        List<Span> spans = new ArrayList<>(ranges.size());
        for (TimeRangeRequest r : ranges) {
            if (r == null) {
                throw new BadRequestException();
            }
            LocalTime end = DayEnd.normalizeEnd(r.getEndTime());
            requireValidRange(r.getStartTime(), end);
            spans.add(new Span(r.getStartTime(), end));
        }
        return spans;
    }

    /**
     * ONCE = 그 하루 / WEEKLY·FOUR_WEEKS = 선택 요일을 1·4주 / MONTH = 선택 요일을 그 달력 월 안에서.
     *
     * <p>반복 모드의 시작점은 {@link #recurrenceStart} — anchor 가 아니다.
     */
    private Set<LocalDate> expandDates(CoverageRequest req) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        RecurrenceMode mode = req.getMode() == null ? RecurrenceMode.ONCE : req.getMode();
        if (mode == RecurrenceMode.ONCE) {
            dates.add(req.getDate());
            return dates;
        }
        List<DayOfWeek> dows = req.getDayOfWeeks();
        if (dows == null || dows.isEmpty()) {
            throw new BadRequestException();
        }
        Set<DayOfWeek> wanted = new LinkedHashSet<>(dows);
        LocalDate start = recurrenceStart(mode, req.getDate());

        if (mode == RecurrenceMode.MONTH) {
            LocalDate last = req.getDate().with(TemporalAdjusters.lastDayOfMonth());
            for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(1)) {
                if (wanted.contains(d.getDayOfWeek())) {
                    dates.add(d);
                }
            }
            return dates;
        }

        int weeks = mode == RecurrenceMode.FOUR_WEEKS ? 4 : 1;
        LocalDate monday = req.getDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int w = 0; w < weeks; w++) {
            LocalDate weekStart = monday.plusWeeks(w);
            for (DayOfWeek dow : wanted) {
                LocalDate d = weekStart.plusDays(dow.getValue() - 1L);
                if (!d.isBefore(start)) {
                    dates.add(d);
                }
            }
        }
        return dates;
    }

    /**
     * 반복 전개 시작점 = {@code max(오늘, 기간 시작)}. 기간 시작은 MONTH 면 그 달 1일, WEEKLY·FOUR_WEEKS 면
     * 그 주 월요일(ISO).
     *
     * <p><b>왜 anchor(요청의 date)가 아닌가</b>: 반복 모드에서 강사가 지정한 건 "어느 기간" 이지 "언제부터" 가
     * 아니다. anchor 를 시작점으로 쓰면 달·주 중간 날을 찍었을 때 기간 앞부분이 <b>조용히</b> 빠진다 —
     * 9/15 를 찍고 "이 달 반복·화" 를 누르면 9/1·9/8 이 사라지고, 9/2 를 찍고 "이 주 반복·월" 을 누르면
     * 아직 미래인 8/31 이 탈락한다. FOUR_WEEKS 가 달 끝(9/29)을 빠뜨려 MONTH 를 만든 것과 같은 부류이고,
     * 에러가 아니라 "덜 열림" 이라 더 나쁘다.
     *
     * <p>오늘로 clamp 하므로 <b>과거는 어떤 경로로도 열리지 않는다</b>. JVM 기본 TZ 는 KST 고정
     * ({@code PungdongApplication}) 이라 여기의 "오늘" 은 강사가 보는 오늘과 같다.
     *
     * <p>클라이언트가 이미 정규화한 날짜를 보내면 이 계산은 그대로 no-op 이다(고정점) — 서버에도 두는 건
     * 규칙이 클라이언트 코드에만 살지 않게 하려는 것. 직접 호출자에게도 "MONTH = 그 달 전체" 가 참이어야 한다.
     */
    private LocalDate recurrenceStart(RecurrenceMode mode, LocalDate anchor) {
        LocalDate periodStart = mode == RecurrenceMode.MONTH
                ? anchor.withDayOfMonth(1)
                : anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate today = LocalDate.now();
        return periodStart.isBefore(today) ? today : periodStart;
    }

    private AvailabilitySession requireOwned(Account me, Long id) {
        AvailabilitySession s = sessionRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (s.getInstructor() == null || !s.getInstructor().getId().equals(me.getId())) {
            throw new ResourceNotFoundException();
        }
        return s;
    }
}
