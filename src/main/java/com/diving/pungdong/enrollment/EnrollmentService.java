package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.SessionCleaner;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.enrollment.dto.EnrollmentResponse;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 수강신청 — 학생 측(신청/취소/내목록). 신청은 서버가 모두 재검증한다: 코스 OPEN·1회차 위치/이용권 · 선택
 * 블록이 venue 운영블록 · <b>그 블록이 강사 coverage(예약가능시간)에 통째로 ⊆</b> · 만석(confirmed+외부hold &lt;
 * 유효정원) · 장비 소속 · 가격 서버 재계산.
 *
 * <p>첫 신청이 그 (위치, 시간블록) {@link AvailabilitySession} 을 생성하고, 같은 (위치, 블록) 신청은 join.
 * 결제는 v1 에 없음(강사 확정 후 PG, 후속).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final CourseJpaRepo courseRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;
    private final SessionCleaner sessionCleaner;

    @Transactional
    public EnrollmentResponse submit(Account student, EnrollmentCreateRequest req) {
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

        // (위치, 블록) session 찾거나 생성 — 첫 신청이 생성, 같은 (위치,블록)이면 join
        AvailabilitySession session = findOrCreateSession(instructor, req.getDate(),
                req.getBlockStart(), req.getBlockEnd(), req.getVenueRefId());

        // 만석 — 확정 + 외부 hold 가 유효정원을 채웠으면 신청 불가(PENDING 은 하드캡 안 함)
        int confirmed = enrollmentRepo.countByAvailabilitySessionIdAndStatus(session.getId(), EnrollmentStatus.CONFIRMED);
        if (confirmed + session.heldCount() >= session.effectiveCapacity()) {
            throw new BadRequestException(); // 만석
        }

        Map<String, VenueEquipmentResponse.Item> items = equipmentItems(instructor, req.getVenueRefId());
        Enrollment e = Enrollment.builder()
                .student(student)
                .course(course)
                .roundIndex(round1 == null || round1.getRoundIndex() == null ? 1 : round1.getRoundIndex())
                .availabilitySession(session)
                .venueRefId(req.getVenueRefId())
                .date(req.getDate())
                .blockStart(req.getBlockStart())
                .blockEnd(req.getBlockEnd())
                .ticketRef(req.getTicketRef())
                .status(EnrollmentStatus.PENDING)
                .tuitionSnapshot(course.getPrice())
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
                e.addEquipment(EnrollmentEquipment.builder()
                        .itemRef(ref).name(item.getName()).priceSnapshot(item.getPrice()).build());
                equipTotal += item.getPrice();
            }
        }
        e.setEquipmentSnapshot(equipTotal);
        enrollmentRepo.save(e);

        return EnrollmentResponse.of(e, venue.getName(), instructor.getNickName());
    }

    @Transactional
    public EnrollmentResponse cancel(Account student, Long id) {
        Enrollment e = requireMine(student, id);
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 중에만 취소
        }
        AvailabilitySession session = e.getAvailabilitySession();
        e.setStatus(EnrollmentStatus.CANCELLED);
        e.setRespondedAt(LocalDateTime.now());
        // 응답은 enrollment 스냅샷 기반(이력 보존). 그 일정이 비면 카드만 삭제(신청 이력은 남음).
        EnrollmentResponse resp = EnrollmentResponse.of(e, venueName(e.getVenueRefId()), instructorName(e));
        sessionCleaner.deleteIfEmpty(session);
        return resp;
    }

    public List<EnrollmentResponse> listMine(Account student) {
        List<Enrollment> list = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId());
        Map<String, String> names = resolveNames(list);
        return list.stream()
                .map(e -> EnrollmentResponse.of(e, names.get(e.getVenueRefId()), instructorName(e)))
                .collect(Collectors.toList());
    }

    /* ─── helpers ─── */

    /** 그 블록이 강사의 그 날 coverage 구간에 통째로 들어가나. */
    private boolean coversWhole(Account instructor, java.time.LocalDate date,
                                java.time.LocalTime start, java.time.LocalTime end) {
        List<Span> spans = coverageRepo.findByInstructorIdAndDate(instructor.getId(), date).stream()
                .map(c -> new Span(c.getStartTime(), c.getEndTime())).collect(Collectors.toList());
        return CoverageMerger.containsWhole(spans, new Span(start, end));
    }

    /** (instructor, date, venueRef, block) session 찾거나 생성. 학생 생성 session 은 정원 override 없음. */
    private AvailabilitySession findOrCreateSession(Account instructor, java.time.LocalDate date,
                                                    java.time.LocalTime start, java.time.LocalTime end, String venueRef) {
        return sessionRepo.findByInstructorIdAndDateAndStartTimeAndEndTime(instructor.getId(), date, start, end)
                .stream().filter(s -> Objects.equals(s.getVenueRefId(), venueRef)).findFirst()
                .orElseGet(() -> sessionRepo.save(AvailabilitySession.builder()
                        .instructor(instructor).date(date).startTime(start).endTime(end)
                        .venueRefId(venueRef).sessionLabel(start + "–" + end)
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

    private Enrollment requireMine(Account student, Long id) {
        Enrollment e = enrollmentRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (e.getStudent() == null || !e.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException();
        }
        return e;
    }

    private String instructorName(Enrollment e) {
        return e.getCourse() == null || e.getCourse().getInstructor() == null
                ? null : e.getCourse().getInstructor().getNickName();
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
