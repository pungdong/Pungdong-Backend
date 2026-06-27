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
import com.diving.pungdong.enrollment.dto.ScheduleHubResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 수강신청 — 학생 측(신청/취소/내목록/일정 hub). 신청은 서버가 모두 재검증한다: 코스 OPEN·1회차 위치/이용권 ·
 * 선택 블록이 venue 운영블록 · <b>그 블록이 강사 coverage(예약가능시간)에 통째로 ⊆</b> · 만석 · 장비 소속 · 가격 재계산.
 *
 * <p>다회차 재설계(2026-06-28): 신청은 <b>수강 컨테이너 {@link Enrollment} + 1회차 {@link EnrollmentRound}</b> 를
 * 생성한다(수강료는 수강에, 슬롯·상태·부대비용은 회차에). 첫 신청이 (위치, 시간블록) {@link AvailabilitySession} 을
 * 생성하고 같은 (위치, 블록) 신청은 join. 2회차+·결제·완료는 후속 PR. API 의 {@code {id}} 는 <b>회차 id</b>.
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

    @Transactional
    public EnrollmentResponse submit(Account student, EnrollmentCreateRequest req) {
        if (!siteSettings.current().launched()) {
            throw new com.diving.pungdong.global.advice.exception.PreLaunchException(); // 런칭 전 전역 신청 차단
        }
        Course course = courseRepo.findById(req.getCourseId())
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
        Account instructor = course.getInstructor();
        if (instructor == null) {
            throw new ResourceNotFoundException();
        }

        CourseRound round1 = firstMeetingRound(course);
        requireRound1Candidate(round1, req.getVenueRefId(), req.getTicketRef());

        // venue 해석 + 선택 블록이 진짜 운영블록인지
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(req.getVenueRefId())).get(req.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = slotDeriver.blocksFor(venue, req.getTicketRef(), req.getDate()).stream()
                .filter(b -> b.sameTime(req.getBlockStart(), req.getBlockEnd()))
                .findFirst().orElseThrow(BadRequestException::new);

        // 블록이 강사 coverage 에 통째로 ⊆ 인지 (부분겹침 불가)
        if (!coversWhole(instructor, req.getDate(), req.getBlockStart(), req.getBlockEnd())) {
            throw new BadRequestException(); // 예약가능시간 밖
        }
        // 강사의 기존 일정과 시간 겹치는 블록이면 거부(이중부킹 방지). 같은 (위치,블록)은 join 이라 통과.
        overlapGuard.requireNoOverlap(instructor.getId(), req.getDate(),
                req.getVenueRefId(), req.getBlockStart(), req.getBlockEnd());

        // (위치, 블록) session 찾거나 생성 — 첫 신청이 생성, 같은 (위치,블록)이면 join
        AvailabilitySession session = findOrCreateSession(instructor, req.getDate(),
                req.getBlockStart(), req.getBlockEnd(), req.getVenueRefId(), req.getTicketRef());

        // 만석 — 점유(결제대기+확정) + 외부 hold 가 유효정원을 채웠으면 신청 불가(PENDING 은 하드캡 안 함)
        int occupying = roundRepo.countByAvailabilitySessionIdAndStatusIn(session.getId(), EnrollmentStatus.OCCUPYING);
        if (occupying + session.heldCount() >= session.effectiveCapacity()) {
            throw new BadRequestException(); // 만석
        }

        Map<String, VenueEquipmentResponse.Item> items = equipmentItems(instructor, req.getVenueRefId());

        Enrollment enrollment = Enrollment.builder()
                .student(student)
                .course(course)
                .tuitionSnapshot(course.getPrice())
                .createdAt(LocalDateTime.now())
                .build();
        EnrollmentRound round = EnrollmentRound.builder()
                .courseRound(round1)
                .roundIndex(round1 == null || round1.getRoundIndex() == null ? 1 : round1.getRoundIndex())
                .roundKind(RoundKind.REGULAR)
                .availabilitySession(session)
                .venueRefId(req.getVenueRefId())
                .date(req.getDate())
                .blockStart(req.getBlockStart())
                .blockEnd(req.getBlockEnd())
                .ticketRef(req.getTicketRef())
                .status(EnrollmentStatus.PENDING)
                .entrySnapshot(block.getFee())
                .createdAt(LocalDateTime.now())
                .build();
        int equipTotal = 0;
        if (req.getEquipmentRefs() != null) {
            for (String ref : req.getEquipmentRefs()) {
                VenueEquipmentResponse.Item item = items.get(ref);
                if (item == null) {
                    throw new BadRequestException(); // 그 위치 장비가 아님
                }
                round.addEquipment(EnrollmentRoundEquipment.builder()
                        .itemRef(ref).name(item.getName()).priceSnapshot(item.getPrice()).build());
                equipTotal += item.getPrice();
            }
        }
        round.setEquipmentSnapshot(equipTotal);
        enrollment.addRound(round);
        enrollmentRepo.save(enrollment); // cascade → round + 장비

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
        // 응답은 회차 스냅샷 기반(이력 보존). 그 일정이 비면 카드만 삭제(신청 이력은 남음).
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
     * 수강생 강의일정 hub — 내 수강을 <b>강의(course=수강 컨테이너) 단위</b>로 묶고 회차 진행상태를 파생.
     * 한 {@link Enrollment} = 한 강의 카드, 그 회차들이 round 행. 추가 조회 없이 스냅샷만 사용(payment·memo 등은 후속).
     * docs/features/student-schedule.md.
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
                        .status(RoundScheduleStatus.from(r.getStatus()))
                        .date(r.getDate())
                        .blockStart(r.getBlockStart())
                        .blockEnd(r.getBlockEnd())
                        .venueRefId(r.getVenueRefId())
                        .venueName(venueNames.get(r.getVenueRefId()))
                        .amount(r.chargeTotal())
                        .rejectionReason(r.getRejectionReason())
                        .createdAt(r.getCreatedAt())
                        .respondedAt(r.getRespondedAt())
                        .build())
                .collect(Collectors.toList());

        CourseScheduleStatus status = CourseScheduleStatus.derive(rounds.stream()
                .map(ScheduleHubResponse.ScheduleRound::getStatus).collect(Collectors.toList()));

        Course course = e.getCourse();
        return ScheduleHubResponse.ScheduleCourse.builder()
                .courseId(course == null ? null : course.getId())
                .title(course == null ? null : course.getTitle())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(course == null ? List.of() : new ArrayList<>(course.getLevels()))
                .instructorName(course == null || course.getInstructor() == null
                        ? null : course.getInstructor().getNickName())
                .status(status)
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

    /* ─── helpers ─── */

    /** 그 블록이 강사의 그 날 coverage 구간에 통째로 들어가나. */
    private boolean coversWhole(Account instructor, java.time.LocalDate date,
                                java.time.LocalTime start, java.time.LocalTime end) {
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(start, end));
    }

    /**
     * (instructor, date, venueRef, block) session 찾거나 생성 — 정체성은 (위치,시간), ticketRef 는 표시 대표값
     * (첫 신청 것으로 저장; 같은 (위치,시간)에 다른 이용권이면 기존 세션에 join, 정원 공유). 학생 생성은 override 없음.
     */
    private AvailabilitySession findOrCreateSession(Account instructor, java.time.LocalDate date,
                                                    java.time.LocalTime start, java.time.LocalTime end,
                                                    String venueRef, String ticketRef) {
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
                .orElse(course.getRounds().isEmpty() ? null : course.getRounds().get(0));
    }

    private void requireRound1Candidate(CourseRound round1, String venueRefId, String ticketRef) {
        boolean ok = round1 != null && round1.getVenues().stream()
                .filter(rv -> rv.getVenueRefId().equals(venueRefId))
                .flatMap(rv -> rv.getTickets().stream())
                .anyMatch(t -> ticketRef.equals(t.getTicketRef()));
        if (!ok) {
            throw new BadRequestException(); // 코스 1회차 위치/이용권이 아님
        }
    }

    private Map<String, VenueEquipmentResponse.Item> equipmentItems(Account instructor, String venueRefId) {
        return equipmentService.findMine(instructor, venueRefId)
                .map(e -> e.getItems() == null ? Map.<String, VenueEquipmentResponse.Item>of()
                        : e.getItems().stream().collect(Collectors.toMap(
                                i -> String.valueOf(i.getId()), i -> i, (a, b) -> a)))
                .orElse(Map.of());
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
