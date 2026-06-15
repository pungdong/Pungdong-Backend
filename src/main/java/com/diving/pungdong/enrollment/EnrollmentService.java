package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityWindow;
import com.diving.pungdong.availability.AvailabilityWindowJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.course.RoundVenue;
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
import java.util.stream.Collectors;

/**
 * 수강신청 — 학생 측(신청/취소/내목록). 신청은 서버가 모두 재검증한다: window 소유 강사=코스 강사 · 선택
 * 블록이 venue 운영블록이며 강사 가용시간에 ⊆ · exact-match(이미 찬 window 면 같은 venue+블록) · 만석
 * (confirmed+외부hold &lt; capacity) · 장비 소속 · 가격 서버 재계산.
 *
 * <p>첫 active 신청이 window 를 (venue, 세션) 으로 bind({@link WindowBinder}) → availability 캘린더가 그
 * window 를 그 위치/세션으로 표시. 결제는 v1 에 없음(강사 확정 후 PG, 후속).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentJpaRepo enrollmentRepo;
    private final CourseJpaRepo courseRepo;
    private final AvailabilityWindowJpaRepo windowRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;
    private final WindowBinder windowBinder;

    @Transactional
    public EnrollmentResponse submit(Account student, EnrollmentCreateRequest req) {
        Course course = courseRepo.findById(req.getCourseId())
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
        AvailabilityWindow window = windowRepo.findById(req.getAvailabilityWindowId())
                .orElseThrow(ResourceNotFoundException::new);
        // window 소유 강사 == 코스 강사 (아니면 잘못된 슬롯 — 존재 숨김)
        if (window.getInstructor() == null || course.getInstructor() == null
                || !window.getInstructor().getId().equals(course.getInstructor().getId())) {
            throw new ResourceNotFoundException();
        }

        CourseRound round1 = firstMeetingRound(course);
        requireRound1Candidate(round1, req.getVenueRefId(), req.getTicketRef());

        // venue 해석 + 선택 블록이 진짜 운영블록이며 가용시간에 ⊆ 인지
        VenueResponse venue = venueRefResolver.resolveVenues(List.of(req.getVenueRefId())).get(req.getVenueRefId());
        if (venue == null) {
            throw new BadRequestException();
        }
        BookableSlotDeriver.Block block = slotDeriver.blocksFor(venue, req.getTicketRef(), window.getDate()).stream()
                .filter(b -> b.sameTime(req.getBlockStart(), req.getBlockEnd()))
                .findFirst().orElseThrow(BadRequestException::new);
        if (req.getBlockStart().isBefore(window.getStartTime()) || req.getBlockEnd().isAfter(window.getEndTime())) {
            throw new BadRequestException(); // 블록이 강사 가용시간 밖
        }

        // exact-match: 이미 찬 window 면 같은 venue+정확히 같은 블록만 합류
        List<Enrollment> active = enrollmentRepo.findByAvailabilityWindowIdAndStatusIn(
                window.getId(), List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED));
        if (!active.isEmpty()) {
            Enrollment bound = active.get(0);
            boolean sameSlot = req.getVenueRefId().equals(bound.getVenueRefId())
                    && req.getBlockStart().equals(bound.getBlockStart())
                    && req.getBlockEnd().equals(bound.getBlockEnd());
            if (!sameSlot) {
                throw new BadRequestException(); // 다른 세션이 차지한 슬롯
            }
        }

        // 만석 — 확정 + 외부 hold 가 정원을 채웠으면 신청 불가(PENDING 은 하드캡 안 함)
        int confirmed = enrollmentRepo.countByAvailabilityWindowIdAndStatus(window.getId(), EnrollmentStatus.CONFIRMED);
        if (confirmed + window.heldCount() >= window.getCapacity()) {
            throw new BadRequestException(); // 만석
        }

        // 장비 검증 + 가격 스냅샷
        Map<String, VenueEquipmentResponse.Item> items = equipmentItems(course.getInstructor(), req.getVenueRefId());
        Enrollment e = Enrollment.builder()
                .student(student)
                .course(course)
                .roundIndex(round1 == null || round1.getRoundIndex() == null ? 1 : round1.getRoundIndex())
                .availabilityWindow(window)
                .venueRefId(req.getVenueRefId())
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

        // 첫 active 신청이면 window 를 이 (venue, 세션)으로 bind
        windowBinder.bindIfUnbound(window, req.getVenueRefId(),
                req.getBlockStart() + "–" + req.getBlockEnd());

        return EnrollmentResponse.of(e, venue.getName(),
                course.getInstructor() == null ? null : course.getInstructor().getNickName());
    }

    @Transactional
    public EnrollmentResponse cancel(Account student, Long id) {
        Enrollment e = requireMine(student, id);
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw new BadRequestException(); // 대기 중에만 취소
        }
        e.setStatus(EnrollmentStatus.CANCELLED);
        e.setRespondedAt(LocalDateTime.now());
        windowBinder.unbindIfEmpty(e.getAvailabilityWindow());
        return EnrollmentResponse.of(e, venueName(e.getVenueRefId()), instructorName(e));
    }

    public List<EnrollmentResponse> listMine(Account student) {
        List<Enrollment> list = enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId());
        Map<String, String> names = resolveNames(list);
        return list.stream()
                .map(e -> EnrollmentResponse.of(e, names.get(e.getVenueRefId()), instructorName(e)))
                .collect(Collectors.toList());
    }

    /* ─── helpers ─── */

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
            throw new ResourceNotFoundException(); // 없음/남의 신청 — 존재 숨김
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
