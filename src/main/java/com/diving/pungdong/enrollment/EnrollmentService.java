package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.availability.SessionOverlapGuard;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.enrollment.dto.EnrollmentResponse;
import com.diving.pungdong.enrollment.dto.RoundScheduleRequest;
import com.diving.pungdong.enrollment.dto.RoundSlotInput;
import com.diving.pungdong.enrollment.dto.ScheduleHubResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.PreLaunchException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 수강신청 — 학생 측(1회차 신청/2회차+ 일정신청/취소/일정변경 선택/내목록/일정 hub). 신청·일정은 서버가 모두
 * 재검증한다: 코스 OPEN·그 회차 위치/이용권 · 블록이 venue 운영블록 · 강사 coverage 에 통째로 ⊆ · 만석 · 장비 · 가격.
 *
 * <p>다회차(붕어빵): 신청은 수강 컨테이너 {@link Enrollment} + 회차 {@link EnrollmentRound}. 1회차는 {@link #submit}
 * 이 수강을 만들고, 2회차+는 {@link #scheduleNextRound}(직전 정규회차 CONFIRMED 게이트 — done 추적 후 done 으로 강화).
 * 강사 일정변경요청(제안 날짜)을 학생이 {@link #pickDate} 로 고르면 사전 수락 → 결제 대기. API {@code {id}} = 회차 id.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final CourseJpaRepo courseRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;
    private final SessionCleaner sessionCleaner;
    private final SessionOverlapGuard overlapGuard;
    private final com.diving.pungdong.global.sitesettings.SiteSettingsProvider siteSettings;

    /** 1회차 신청 — 수강 컨테이너 + 첫 만남 회차 생성. */
    @Transactional
    public EnrollmentResponse submit(Account student, EnrollmentCreateRequest req) {
        requireLaunched();
        Course course = openCourse(req.getCourseId());
        Account instructor = requireInstructor(course);
        CourseRound round1 = firstMeetingRound(course);
        if (round1 == null) {
            throw new BadRequestException(); // 코스에 정규 회차 정의 없음
        }
        Enrollment enrollment = Enrollment.builder()
                .student(student).course(course)
                .tuitionSnapshot(course.getPrice())
                .createdAt(LocalDateTime.now())
                .build();
        EnrollmentRound round = buildRound(instructor, round1, req, 0);
        enrollment.addRound(round);
        enrollmentRepo.save(enrollment); // cascade → round + 장비
        return EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructor.getNickName());
    }

    /**
     * 2회차+ 일정 신청 — 다음 schedulable 회차(직전 정규 CONFIRMED 게이트, 정규 다 끝나면 EXTRA)를 PENDING 으로 추가.
     * 슬롯은 1회차와 같은 재검증. EXTRA 는 freeCount 초과분만 추가세션비.
     */
    @Transactional
    public EnrollmentResponse scheduleNextRound(Account student, Long enrollmentId, RoundScheduleRequest req) {
        requireLaunched();
        Enrollment enrollment = enrollmentRepo.findById(enrollmentId).orElseThrow(ResourceNotFoundException::new);
        if (enrollment.getStudent() == null || !enrollment.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 수강 — 존재 숨김
        }
        Course course = enrollment.getCourse();
        if (course == null || course.getStatus() != CourseStatus.OPEN) {
            throw new BadRequestException();
        }
        Account instructor = requireInstructor(course);
        CourseRound next = RoundGate.nextSchedulable(enrollment);
        if (next == null) {
            throw new BadRequestException(); // 지금 잡을 회차 없음(직전 회차 미확정 / 전부 완료)
        }
        int extraSnapshot = next.getRoundKind() == RoundKind.EXTRA ? extraFee(enrollment, next) : 0;
        EnrollmentRound round = buildRound(instructor, next, req, extraSnapshot);
        enrollment.addRound(round);
        roundRepo.save(round);
        return EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructor.getNickName());
    }

    /**
     * 강사 일정변경요청 중 학생이 날짜 선택 — 같은 위치/이용권/블록, 날짜만 변경해 재검증 후 reschedule. 강사가
     * 이미 제안 = 사전 수락이라 곧장 PAYMENT_PENDING(결제 대기). 날짜 바뀌면 세션 재결합 + 입장료 재산정.
     */
    @Transactional
    public EnrollmentResponse pickDate(Account student, Long roundId, LocalDate date) {
        EnrollmentRound round = requireMyRound(student, roundId);
        if (!round.hasRescheduleOffer()) {
            throw new BadRequestException(); // 강사 제안 받은 회차만
        }
        if (round.getProposedDates().stream().noneMatch(d -> d.equals(date))) {
            throw new BadRequestException(); // 제안 목록 밖 날짜
        }
        Account instructor = round.getEnrollment().getCourse().getInstructor();
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(round.getVenueRefId())).get(round.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, round.getTicketRef(), date,
                round.getBlockStart(), round.getBlockEnd());
        requireCoverageAndNoOverlap(instructor, date, round.getVenueRefId(), round.getBlockStart(), round.getBlockEnd());

        AvailabilitySession oldSession = round.getAvailabilitySession();
        AvailabilitySession newSession = findOrCreateSession(instructor, date,
                round.getBlockStart(), round.getBlockEnd(), round.getVenueRefId(), round.getTicketRef());
        requireSeat(newSession); // 이 회차는 아직 새 세션에 없음 — 순수 잔여 확인

        round.setAvailabilitySession(newSession);
        round.setDate(date);
        round.setEntrySnapshot(block.getFee()); // 새 날짜 daypart 입장료
        round.getProposedDates().clear();
        round.setStatus(EnrollmentStatus.PAYMENT_PENDING); // 강사 사전 수락 → 결제 대기
        round.setRespondedAt(LocalDateTime.now());
        if (oldSession != null && !oldSession.getId().equals(newSession.getId())) {
            sessionCleaner.deleteIfEmpty(oldSession);
        }
        return EnrollmentResponse.of(round, venue.getName(), instructor.getNickName());
    }

    /**
     * 회차 취소 — <b>결제 전(PENDING·PAYMENT_PENDING)만 무료</b>. pay-first 라 강사가 풀을 안 잡았으니 손해 0.
     * 결제 후(CONFIRMED) 취소는 환불 거래(후속 PR). {@code roundId} = 회차 id.
     */
    @Transactional
    public EnrollmentResponse cancel(Account student, Long roundId) {
        EnrollmentRound round = requireMyRound(student, roundId);
        if (round.getStatus() != EnrollmentStatus.PENDING && round.getStatus() != EnrollmentStatus.PAYMENT_PENDING) {
            throw new BadRequestException(); // 결제 전에만 무료 취소
        }
        AvailabilitySession session = round.getAvailabilitySession();
        round.setStatus(EnrollmentStatus.CANCELLED);
        round.setRespondedAt(LocalDateTime.now());
        EnrollmentResponse resp = EnrollmentResponse.of(round, venueName(round.getVenueRefId()), instructorName(round));
        sessionCleaner.deleteIfEmpty(session);
        return resp;
    }

    /** 내 회차 목록(평탄) — 최신 수강 먼저, 그 안 회차들. */
    public List<EnrollmentResponse> listMine(Account student) {
        List<EnrollmentRound> rounds = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId()).stream()
                .flatMap(e -> e.getRounds().stream()).collect(Collectors.toList());
        Map<String, String> names = resolveNames(rounds);
        return rounds.stream()
                .map(r -> EnrollmentResponse.of(r, names.get(r.getVenueRefId()), instructorName(r)))
                .collect(Collectors.toList());
    }

    /**
     * 수강생 강의일정 hub — 내 수강을 강의(course=수강 컨테이너) 단위로 묶고 회차 진행상태를 파생. 한 {@link Enrollment}
     * = 한 강의 카드. 잡은 회차는 {@code rounds[]}, 미래 회차는 {@code totalRounds}/{@code nextRoundIndex} 로 FE 가 그림.
     */
    public ScheduleHubResponse mySchedule(Account student) {
        List<Enrollment> enrollments = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId());
        Map<String, String> venueNames = resolveNames(
                enrollments.stream().flatMap(e -> e.getRounds().stream()).collect(Collectors.toList()));

        List<ScheduleHubResponse.ScheduleCourse> courses = enrollments.stream()
                .map(e -> buildScheduleCourse(e, venueNames))
                .sorted(Comparator.comparingInt(c -> CourseScheduleStatus.ORDER.indexOf(c.getStatus())))
                .collect(Collectors.toList());

        return new ScheduleHubResponse(buildScheduleFilters(courses), courses);
    }

    private ScheduleHubResponse.ScheduleCourse buildScheduleCourse(Enrollment e, Map<String, String> venueNames) {
        List<ScheduleHubResponse.ScheduleRound> rounds = e.getRounds().stream()
                .sorted(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .map(r -> ScheduleHubResponse.ScheduleRound.builder()
                        .roundId(r.getId())
                        .roundIndex(r.getRoundIndex())
                        .roundKind(r.getRoundKind() == null ? null : r.getRoundKind().name())
                        .status(RoundScheduleStatus.from(r))
                        .date(r.getDate())
                        .blockStart(r.getBlockStart())
                        .blockEnd(r.getBlockEnd())
                        .venueRefId(r.getVenueRefId())
                        .venueName(venueNames.get(r.getVenueRefId()))
                        .amount(r.chargeTotal())
                        .proposedDates(new ArrayList<>(r.getProposedDates()))
                        .rejectionReason(r.getRejectionReason())
                        .createdAt(r.getCreatedAt())
                        .respondedAt(r.getRespondedAt())
                        .build())
                .collect(Collectors.toList());

        CourseScheduleStatus status = CourseScheduleStatus.derive(rounds.stream()
                .map(ScheduleHubResponse.ScheduleRound::getStatus).collect(Collectors.toList()));

        Course course = e.getCourse();
        int totalRounds = course == null ? 0 : (int) course.getRounds().stream()
                .filter(cr -> cr.getRoundKind() == RoundKind.REGULAR).count();
        CourseRound next = course == null ? null : RoundGate.nextSchedulable(e);
        Integer nextRoundIndex = next != null && next.getRoundKind() == RoundKind.REGULAR ? next.getRoundIndex() : null;
        boolean canScheduleExtra = next != null && next.getRoundKind() == RoundKind.EXTRA;

        return ScheduleHubResponse.ScheduleCourse.builder()
                .courseId(course == null ? null : course.getId())
                .title(course == null ? null : course.getTitle())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(course == null ? List.of() : new ArrayList<>(course.getLevels()))
                .instructorName(course == null || course.getInstructor() == null
                        ? null : course.getInstructor().getNickName())
                .status(status)
                .totalRounds(totalRounds)
                .nextRoundIndex(nextRoundIndex)
                .canScheduleExtra(canScheduleExtra)
                .enrollmentId(e.getId())
                .rounds(rounds)
                .build();
    }

    private static final Map<CourseScheduleStatus, String> COURSE_STATUS_LABEL = Map.of(
            CourseScheduleStatus.PAYMENT_DUE, "결제 대기",
            CourseScheduleStatus.RESCHEDULING, "일정 변경",
            CourseScheduleStatus.WAITING, "수락 대기",
            CourseScheduleStatus.PROGRESS, "진행중",
            CourseScheduleStatus.CANCELLED, "취소");

    private List<ScheduleHubResponse.FilterCount> buildScheduleFilters(
            List<ScheduleHubResponse.ScheduleCourse> courses) {
        List<ScheduleHubResponse.FilterCount> filters = new ArrayList<>();
        filters.add(new ScheduleHubResponse.FilterCount("all", "전체", courses.size()));
        for (CourseScheduleStatus s : CourseScheduleStatus.ORDER) {
            int count = (int) courses.stream().filter(c -> c.getStatus() == s).count();
            filters.add(new ScheduleHubResponse.FilterCount(s.name(), COURSE_STATUS_LABEL.get(s), count));
        }
        return filters;
    }

    /* ─── 공유 회차 빌더 + 게이트 ─── */

    /** 슬롯 재검증(코스 회차 후보·블록·coverage·overlap·세션·만석·장비·가격) 후 PENDING 회차 생성(미부착). */
    private EnrollmentRound buildRound(Account instructor, CourseRound courseRound, RoundSlotInput slot, int extraSnapshot) {
        requireRoundCandidate(courseRound, slot.getVenueRefId(), slot.getTicketRef());
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(slot.getVenueRefId())).get(slot.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = bookableBlock(venue, slot.getTicketRef(), slot.getDate(),
                slot.getBlockStart(), slot.getBlockEnd());
        requireCoverageAndNoOverlap(instructor, slot.getDate(), slot.getVenueRefId(),
                slot.getBlockStart(), slot.getBlockEnd());
        AvailabilitySession session = findOrCreateSession(instructor, slot.getDate(),
                slot.getBlockStart(), slot.getBlockEnd(), slot.getVenueRefId(), slot.getTicketRef());
        requireSeat(session);

        Map<String, VenueEquipmentResponse.Item> items = equipmentItems(instructor, slot.getVenueRefId());
        EnrollmentRound round = EnrollmentRound.builder()
                .courseRound(courseRound)
                .roundIndex(courseRound.getRoundIndex())
                .roundKind(courseRound.getRoundKind())
                .availabilitySession(session)
                .venueRefId(slot.getVenueRefId())
                .date(slot.getDate())
                .blockStart(slot.getBlockStart())
                .blockEnd(slot.getBlockEnd())
                .ticketRef(slot.getTicketRef())
                .status(EnrollmentStatus.PENDING)
                .entrySnapshot(block.getFee())
                .extraSnapshot(extraSnapshot)
                .createdAt(LocalDateTime.now())
                .build();
        round.setEquipmentSnapshot(addEquipment(round, slot.getEquipmentRefs(), items));
        return round;
    }

    private int addEquipment(EnrollmentRound round, List<String> refs, Map<String, VenueEquipmentResponse.Item> items) {
        int total = 0;
        if (refs != null) {
            for (String ref : refs) {
                VenueEquipmentResponse.Item item = items.get(ref);
                if (item == null) {
                    throw new BadRequestException(); // 그 위치 장비가 아님
                }
                round.addEquipment(EnrollmentRoundEquipment.builder()
                        .itemRef(ref).name(item.getName()).priceSnapshot(item.getPrice()).build());
                total += item.getPrice();
            }
        }
        return total;
    }

    /** 만석 — 신청 시점 좌석 lock(선착순): 활성 + 외부 hold 가 유효정원을 채웠으면 거부. */
    private void requireSeat(AvailabilitySession session) {
        int occupied = roundRepo.countByAvailabilitySessionIdAndStatusIn(session.getId(), EnrollmentStatus.ACTIVE);
        if (occupied + session.heldCount() >= session.effectiveCapacity()) {
            throw new BadRequestException(); // 만석
        }
    }

    private int extraFee(Enrollment enrollment, CourseRound extra) {
        long existingExtra = enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.EXTRA && r.getStatus().isActive()).count();
        int freeCount = extra.getFreeCount() == null ? 0 : extra.getFreeCount();
        int perSession = extra.getPerSessionPrice() == null ? 0 : extra.getPerSessionPrice();
        return existingExtra < freeCount ? 0 : perSession;
    }

    /* ─── 검증 helpers ─── */

    private BookableSlotDeriver.Block bookableBlock(VenueResponse venue, String ticketRef, LocalDate date,
                                                    LocalTime start, LocalTime end) {
        return slotDeriver.blocksFor(venue, ticketRef, date).stream()
                .filter(b -> b.sameTime(start, end))
                .findFirst().orElseThrow(BadRequestException::new);
    }

    /** 블록이 강사 coverage 에 통째로 ⊆ + 강사 기존 일정과 시간 안 겹침(같은 위치/블록 join 제외). */
    private void requireCoverageAndNoOverlap(Account instructor, LocalDate date, String venueRef,
                                             LocalTime start, LocalTime end) {
        if (!coversWhole(instructor, date, start, end)) {
            throw new BadRequestException(); // 예약가능시간 밖
        }
        overlapGuard.requireNoOverlap(instructor.getId(), date, venueRef, start, end);
    }

    private boolean coversWhole(Account instructor, LocalDate date, LocalTime start, LocalTime end) {
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(start, end));
    }

    private AvailabilitySession findOrCreateSession(Account instructor, LocalDate date,
                                                    LocalTime start, LocalTime end, String venueRef, String ticketRef) {
        return sessionRepo.findByInstructorIdAndDateAndStartTimeAndEndTime(instructor.getId(), date, start, end)
                .stream().filter(s -> Objects.equals(s.getVenueRefId(), venueRef)).findFirst()
                .orElseGet(() -> sessionRepo.save(AvailabilitySession.builder()
                        .instructor(instructor).date(date).startTime(start).endTime(end)
                        .venueRefId(venueRef).ticketRef(ticketRef)
                        .createdAt(LocalDateTime.now()).build()));
    }

    private CourseRound firstMeetingRound(Course course) {
        return course.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                .min(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .orElse(null);
    }

    private void requireRoundCandidate(CourseRound courseRound, String venueRefId, String ticketRef) {
        boolean ok = courseRound != null && courseRound.getVenues().stream()
                .filter(rv -> rv.getVenueRefId().equals(venueRefId))
                .flatMap(rv -> rv.getTickets().stream())
                .anyMatch(t -> ticketRef.equals(t.getTicketRef()));
        if (!ok) {
            throw new BadRequestException(); // 그 회차의 위치/이용권이 아님
        }
    }

    private Map<String, VenueEquipmentResponse.Item> equipmentItems(Account instructor, String venueRefId) {
        return equipmentService.findMine(instructor, venueRefId)
                .map(e -> e.getItems() == null ? Map.<String, VenueEquipmentResponse.Item>of()
                        : e.getItems().stream().collect(Collectors.toMap(
                                i -> String.valueOf(i.getId()), i -> i, (a, b) -> a)))
                .orElse(Map.of());
    }

    private void requireLaunched() {
        if (!siteSettings.current().launched()) {
            throw new PreLaunchException(); // 런칭 전 전역 신청 차단
        }
    }

    private Course openCourse(Long courseId) {
        return courseRepo.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private Account requireInstructor(Course course) {
        Account instructor = course.getInstructor();
        if (instructor == null) {
            throw new ResourceNotFoundException();
        }
        return instructor;
    }

    private EnrollmentRound requireMyRound(Account student, Long roundId) {
        EnrollmentRound r = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Account owner = r.getEnrollment() == null ? null : r.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
        return r;
    }

    private String instructorName(EnrollmentRound r) {
        Course c = r.getEnrollment() == null ? null : r.getEnrollment().getCourse();
        return c == null || c.getInstructor() == null ? null : c.getInstructor().getNickName();
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
