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
 *
 * <p>날짜 window: <b>오늘부터 {@code LOOKAHEAD_WEEKS}주(8주)</b> 안에서 강사 coverage 가 있는 날만 슬롯이 난다
 * (coverage 끝이 더 가까우면 거기서 끝). 일정수정·강사제안은 추가로 회차가 잡은 venue 1개로 좁힌다(위치 고정).
 * 같은 (날짜,위치,이용권,블록) 슬롯은 한 번만 — 중복 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentOptionsService {

    /** 옵션을 내다볼 미래 범위 — 오늘부터 8주. */
    private static final int LOOKAHEAD_WEEKS = 8;

    private final CourseJpaRepo courseRepo;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final AvailabilityCoverageJpaRepo coverageRepo;
    private final AvailabilitySessionJpaRepo sessionRepo;
    private final EnrollmentRoundJpaRepo roundRepo;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final BookableSlotDeriver slotDeriver;

    /** 1회차 신청 옵션 — 코스 첫 만남 회차의 교집합. */
    public EnrollmentOptionsResponse getOptions(Account student, Long courseId, LocalDate today) {
        Course course = courseRepo.findById(courseId)
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .orElseThrow(ResourceNotFoundException::new);
        return buildOptions(course.getInstructor(), course, firstMeetingRound(course), today, null);
    }

    /** 다음 회차 옵션 — 이 수강의 다음 schedulable 회차(게이트)의 교집합. 잡을 회차 없으면 슬롯 빈 응답. */
    public EnrollmentOptionsResponse getNextOptions(Account student, Long enrollmentId, LocalDate today) {
        Enrollment enrollment = enrollmentRepo.findById(enrollmentId).orElseThrow(ResourceNotFoundException::new);
        if (enrollment.getStudent() == null || !enrollment.getStudent().getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 수강 — 존재 숨김
        }
        Course course = enrollment.getCourse();
        if (course == null) {
            throw new ResourceNotFoundException();
        }
        CourseRound next = RoundGate.nextSchedulable(enrollment);
        if (next == null) {
            return EnrollmentOptionsResponse.builder()
                    .course(courseSummary(course, null))
                    .slots(List.of())
                    .equipmentByVenue(Map.of())
                    .build();
        }
        return buildOptions(course.getInstructor(), course, next, today, null);
    }

    /** 회차 직접 일정수정용 옵션 — 그 회차의 CourseRound 교집합 슬롯(1회차 옵션과 동일 shape). */
    public EnrollmentOptionsResponse getRoundOptions(Account student, Long roundId, LocalDate today) {
        EnrollmentRound round = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Account owner = round.getEnrollment() == null ? null : round.getEnrollment().getStudent();
        if (owner == null || !owner.getId().equals(student.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 회차 — 존재 숨김
        }
        Course course = round.getEnrollment().getCourse();
        if (course == null) {
            throw new ResourceNotFoundException();
        }
        // 일정수정은 위치 고정 — 그 회차가 잡은 venue 로만 좁힌다(다른 후보 위치 슬롯 섞임 방지).
        return buildOptions(course.getInstructor(), course, round.getCourseRound(), today, round.getVenueRefId());
    }

    /**
     * 강사 일정변경 제안용 옵션 — 강사가 그 회차에 대안 슬롯을 고를 때 보는 교집합(학생 round options 와 대칭).
     * {@code remaining/full} 을 그대로 내려줘 강사가 <b>만석 슬롯을 애초에 안 고르게</b> 한다(제안 보장 hold 도
     * heldCount 로 잔여에 반영됨). 위치는 회차 고정이라 그 회차 CourseRound 후보가 곧 후보. 남의 회차면 404(숨김).
     */
    public EnrollmentOptionsResponse getInstructorRoundOptions(Account instructor, Long roundId, LocalDate today) {
        EnrollmentRound round = roundRepo.findById(roundId).orElseThrow(ResourceNotFoundException::new);
        Course course = round.getEnrollment() == null ? null : round.getEnrollment().getCourse();
        Account owner = course == null ? null : course.getInstructor();
        if (owner == null || !owner.getId().equals(instructor.getId())) {
            throw new ResourceNotFoundException(); // 없음/내 코스 회차 아님 — 존재 숨김
        }
        // 일정변경 제안은 위치 고정(회차가 잡은 venue) — 그 venue 슬롯만 후보로 좁힌다.
        return buildOptions(owner, course, round.getCourseRound(), today, round.getVenueRefId());
    }

    /**
     * @param venueScope 위치 고정 스코프 — 일정수정·강사제안은 회차가 잡은 venueRefId 1개로 좁힌다(다른 후보
     *                   위치 슬롯이 섞이지 않게). 신규 신청(1회차·다음회차)은 {@code null}(회차의 모든 후보 위치).
     */
    private EnrollmentOptionsResponse buildOptions(Account instructor, Course course, CourseRound round,
                                                   LocalDate today, String venueScope) {
        // 회차 후보 (venueRef, ticketRef) 쌍 — venueScope 가 주어지면 그 venue 후보만.
        List<String[]> candidates = new ArrayList<>();
        Set<String> venueRefs = new LinkedHashSet<>();
        if (round != null) {
            for (RoundVenue rv : round.getVenues()) {
                if (venueScope != null && !venueScope.equals(rv.getVenueRefId())) {
                    continue; // 회차 고정 위치 밖 — 제외
                }
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
        Set<String> seen = new LinkedHashSet<>(); // (날짜,위치,이용권,블록) — 후보 중복으로 같은 슬롯이 두 번 나오지 않게
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
                    if (!seen.add(slotKey(date, venueRef, ticketRef, b.getStart(), b.getEnd()))) {
                        continue; // 같은 (날짜,위치,이용권,블록) 슬롯 중복 — 한 번만 내려준다
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
                            .ticketName(ticketName(vr, ticketRef))
                            .entryFee(b.getFee())
                            .capacity(capacity)
                            .remaining(remaining)
                            .full(remaining <= 0)
                            .build());
                }
            }
        }

        return EnrollmentOptionsResponse.builder()
                .course(courseSummary(course, round))
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

    /** 슬롯 동일성 키 — (날짜,위치,이용권,블록). 같은 이용권은 같은 슬롯(중복 제거 기준). */
    private static String slotKey(LocalDate date, String venueRef, String ticketRef, LocalTime start, LocalTime end) {
        return date + "|" + venueRef + "|" + ticketRef + "|" + start + "|" + end;
    }

    /** 그 위치의 이용권 표시명(ticketRef 매칭) — CourseDetail 합성과 같은 출처({@code VenueResponse.Ticket.name}). */
    private static String ticketName(VenueResponse vr, String ticketRef) {
        if (vr.getTickets() == null) {
            return null;
        }
        return vr.getTickets().stream()
                .filter(t -> ticketRef.equals(t.getTicketRef()))
                .map(VenueResponse.Ticket::getName)
                .findFirst().orElse(null);
    }

    private CourseRound firstMeetingRound(Course course) {
        return course.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR)
                .min(Comparator.comparing(r -> r.getRoundIndex() == null ? Integer.MAX_VALUE : r.getRoundIndex()))
                .orElse(course.getRounds().isEmpty() ? null : course.getRounds().get(0));
    }

    private EnrollmentOptionsResponse.CourseSummary courseSummary(Course course, CourseRound round) {
        String roundLabel;
        if (round == null) {
            roundLabel = "다음 회차 없음";
        } else if (round.getRoundKind() == RoundKind.EXTRA) {
            roundLabel = "추가 세션";
        } else {
            int idx = round.getRoundIndex() == null ? 1 : round.getRoundIndex();
            roundLabel = idx + "회차" + (idx == 1 ? " · 첫 만남" : "");
        }
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
