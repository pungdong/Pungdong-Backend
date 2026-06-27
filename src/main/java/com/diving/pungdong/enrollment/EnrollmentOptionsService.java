package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.availability.CoverageMerger;
import com.diving.pungdong.availability.CoverageMerger.Span;
import com.diving.pungdong.availability.SessionOverlapGuard;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.course.RoundVenue;
import com.diving.pungdong.enrollment.dto.EnrollmentOptionsResponse;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 수강신청 옵션(교집합) — <b>강사 coverage(예약가능시간) ∩ venue 운영블록 ∩ 코스 1회차 위치</b>를 계산해 평탄
 * 슬롯 집합을 만든다. venue 부 전체가 coverage 에 ⊆ 일 때만 옵션이 된다(부분겹침 불가).
 *
 * <p>슬롯 정원: 그 (위치,블록)에 일정(session)이 있으면 session.effectiveCapacity()·점유, 없으면 계정 기본값·0점유.
 * remaining = 정원 − 확정 − 외부 hold.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentOptionsService {

    /** 옵션을 내다볼 미래 범위 — 오늘부터 8주. */
    private static final int LOOKAHEAD_WEEKS = 8;

    private final CourseJpaRepo courseRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;

    public EnrollmentOptionsResponse getOptions(Account student, Long courseId, LocalDate today) {
        Course course = courseRepo.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
        Account instructor = course.getInstructor();
        CourseRound round1 = firstMeetingRound(course);

        // 1회차 후보 (venueRef, ticketRef) 쌍
        List<String[]> candidates = new ArrayList<>();
        Set<String> venueRefs = new LinkedHashSet<>();
        if (round1 != null) {
            for (RoundVenue rv : round1.getVenues()) {
                venueRefs.add(rv.getVenueRefId());
                rv.getTickets().forEach(t -> candidates.add(new String[]{rv.getVenueRefId(), t.getTicketRef()}));
            }
        }

        Map<String, VenueResponse> venueByRef = venueRefResolver.resolveVenues(venueRefs);
        Map<String, VenueEquipmentResponse> equipByRef = new LinkedHashMap<>();
        for (String ref : venueRefs) {
            equipmentService.findMine(instructor, ref).ifPresent(e -> equipByRef.put(ref, e));
        }

        LocalDate to = today.plusWeeks(LOOKAHEAD_WEEKS);
        int defaultCapacity = instructor.effectiveDefaultCapacity();

        // coverage(날짜별 머지 구간) + session 점유 일괄
        Map<LocalDate, List<Span>> coverageByDate = coverageByDate(instructor.getId(), today, to);
        Map<String, AvailabilitySession> sessionByKey = new LinkedHashMap<>();
        List<AvailabilitySession> sessions = sessionRepo
                .findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(instructor.getId(), today, to);
        sessions.forEach(s -> sessionByKey.put(
                sessionKey(s.getDate(), s.getVenueRefId(), s.getStartTime(), s.getEndTime()), s));
        Map<LocalDate, List<AvailabilitySession>> sessionsByDate = sessions.stream()
                .collect(Collectors.groupingBy(AvailabilitySession::getDate));
        // 점유 합산 — 남은 좌석 계산. 신청 시점 좌석 lock 이라 활성(대기+결제대기+확정) 전부 점유로 친다.
        Map<Long, Integer> occupiedBySession = roundRepo
                .findByAvailabilitySessionIdInAndStatusIn(
                        sessions.stream().map(AvailabilitySession::getId).collect(Collectors.toList()),
                        EnrollmentStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getAvailabilitySession().getId(),
                        Collectors.summingInt(r -> 1)));

        List<EnrollmentOptionsResponse.Slot> slots = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Span>> day : coverageByDate.entrySet()) {
            LocalDate date = day.getKey();
            List<Span> spans = day.getValue();
            for (String[] c : candidates) {
                String venueRef = c[0];
                String ticketRef = c[1];
                VenueResponse vr = venueByRef.get(venueRef);
                if (vr == null) {
                    continue;
                }
                for (BookableSlotDeriver.Block b : slotDeriver.blocksFor(vr, ticketRef, date)) {
                    if (!CoverageMerger.containsWhole(spans, new Span(b.getStart(), b.getEnd()))) {
                        continue; // venue 부가 coverage 에 통째로 안 들어옴
                    }
                    if (SessionOverlapGuard.wouldOverlap(sessionsByDate.getOrDefault(date, List.of()),
                            venueRef, b.getStart(), b.getEnd())) {
                        continue; // 강사 기존 일정과 시간 겹침 — 이중부킹 불가(같은 위치·블록 join 은 제외)
                    }
                    // 정원은 물리 슬롯(위치,시간) 공유 — 같은 시간 다른 이용권도 같은 session 점유를 본다.
                    AvailabilitySession s = sessionByKey.get(sessionKey(date, venueRef, b.getStart(), b.getEnd()));
                    int capacity = s == null ? defaultCapacity : s.effectiveCapacity();
                    int occupied = s == null ? 0
                            : occupiedBySession.getOrDefault(s.getId(), 0) + s.heldCount();
                    int remaining = Math.max(0, capacity - occupied);
                    slots.add(EnrollmentOptionsResponse.Slot.builder()
                            .date(date)
                            .venueRefId(venueRef)
                            .venueName(vr.getName())
                            .venueType(vr.getType())
                            .area(vr.getAddress())
                            .blockStart(b.getStart())
                            .blockEnd(b.getEnd())
                            .sessionLabel(label(b.getStart(), b.getEnd()))
                            .ticketRef(ticketRef)
                            .entryFee(b.getFee())
                            .capacity(capacity)
                            .remaining(remaining)
                            .full(remaining <= 0)
                            .build());
                }
            }
        }

        return EnrollmentOptionsResponse.builder()
                .course(courseSummary(course, round1))
                .slots(slots)
                .equipmentByVenue(equipmentOptions(equipByRef))
                .build();
    }

    /** 날짜별 머지된 coverage 구간 — 날짜 오름차순. */
    private Map<LocalDate, List<Span>> coverageByDate(Long instructorId, LocalDate from, LocalDate to) {
        Map<LocalDate, List<Span>> raw = new TreeMap<>();
        for (AvailabilityCoverage c : coverageRepo
                .findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(instructorId, from, to)) {
            raw.computeIfAbsent(c.getDate(), k -> new ArrayList<>()).add(new Span(c.getStartTime(), c.getEndTime()));
        }
        raw.replaceAll((d, spans) -> CoverageMerger.normalize(spans));
        return raw;
    }

    private static String sessionKey(LocalDate date, String venueRef, LocalTime start, LocalTime end) {
        return date + "|" + venueRef + "|" + start + "|" + end;
    }

    private CourseRound firstMeetingRound(Course course) {
        return course.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                .min(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .orElse(course.getRounds().isEmpty() ? null : course.getRounds().get(0));
    }

    private EnrollmentOptionsResponse.CourseSummary courseSummary(Course course, CourseRound round1) {
        String roundLabel = round1 == null ? "1회차 · 첫 만남"
                : (round1.getRoundIndex() == null ? 1 : round1.getRoundIndex()) + "회차 · 첫 만남";
        Account ins = course.getInstructor();
        return EnrollmentOptionsResponse.CourseSummary.builder()
                .id(course.getId())
                .title(course.getTitle())
                .disciplineCode(course.getDisciplineCode())
                .levels(course.getLevels())
                .price(course.getPrice())
                .roundLabel(roundLabel)
                .instructorId(ins == null ? null : ins.getId())
                .instructorName(ins == null ? null : ins.getNickName())
                .build();
    }

    private Map<String, List<EnrollmentOptionsResponse.EquipmentOption>> equipmentOptions(
            Map<String, VenueEquipmentResponse> equipByRef) {
        Map<String, List<EnrollmentOptionsResponse.EquipmentOption>> out = new LinkedHashMap<>();
        equipByRef.forEach((ref, e) -> {
            if (e.getItems() == null) {
                return;
            }
            out.put(ref, e.getItems().stream()
                    .map(i -> EnrollmentOptionsResponse.EquipmentOption.builder()
                            .itemRef(String.valueOf(i.getId()))
                            .name(i.getName())
                            .price(i.getPrice())
                            .sizeFormat(i.getSizeFormat() == null ? null : i.getSizeFormat().name())
                            .sizeOptions(i.getSizeOptions())
                            .build())
                    .collect(Collectors.toList()));
        });
        return out;
    }

    static String label(LocalTime start, LocalTime end) {
        return start + "–" + end;
    }
}
