package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.dto.AvailabilityCreateRequest;
import com.diving.pungdong.availability.dto.AvailabilityUpdateRequest;
import com.diving.pungdong.availability.dto.AvailabilityWindowResponse;
import com.diving.pungdong.availability.dto.ApplicantSummaryResponse;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentEquipment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.VenueRefValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강사 가용시간 캘린더 — window 생성(반복 전개)/조회/수정/삭제 + 점유 hold 추가·제거 + 5상태 파생.
 *
 * <p>2층 모델: window(이론적 가능성) 는 이 도메인이 소유, 점유는 {@link AvailabilityHold}(외부/수동)만
 * v1 에서 다룬다. 풍덩 수강생 점유(pending/confirmed)는 미래 enrollment 도메인 — v1 응답엔 0/빈 배열.
 *
 * <p>게이트 = 강사신청 보유(상태 무관, venue 도메인과 동일 기조). 없음/비소유 window = 400
 * ({@link ResourceNotFoundException}, 남의 일정 존재 숨김). 응답은 트랜잭션 안에서 DTO 매핑(LAZY hold 보호).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvailabilityService {

    private final AvailabilityWindowJpaRepo windowRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefValidator venueRefValidator;
    private final VenueRefResolver venueRefResolver;
    private final EnrollmentJpaRepo enrollmentRepo;

    /* ─── 생성 (반복 전개) ─────────────────────────────────── */

    @Transactional
    public List<AvailabilityWindowResponse> create(Account instructor, AvailabilityCreateRequest req) {
        requireInstructorTrack(instructor);
        requireValidRange(req.getStartTime(), req.getEndTime());
        requireValidCapacity(req.getCapacity());
        validateVenueRefIfPresent(instructor, req.getVenueRefId());

        Set<LocalDate> dates = expandDates(req);
        if (dates.isEmpty()) {
            throw new BadRequestException(); // 전개 결과가 비면(예: 과거 요일만) 만들 게 없다
        }

        LocalDateTime now = LocalDateTime.now();
        return dates.stream()
                .map(d -> windowRepo.save(AvailabilityWindow.builder()
                        .instructor(instructor)
                        .date(d)
                        .startTime(req.getStartTime())
                        .endTime(req.getEndTime())
                        .capacity(req.getCapacity())
                        .venueRefId(StringUtils.hasText(req.getVenueRefId()) ? req.getVenueRefId().trim() : null)
                        .sessionLabel(StringUtils.hasText(req.getSessionLabel()) ? req.getSessionLabel().trim() : null)
                        .createdAt(now)
                        .build()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /** ONCE = 그 하루 / WEEKLY·FOUR_WEEKS = 선택 요일을 1주·4주에 걸쳐(기준일이 속한 주부터, 과거일 제외). */
    private Set<LocalDate> expandDates(AvailabilityCreateRequest req) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (req.getMode() == RecurrenceMode.ONCE) {
            dates.add(req.getDate());
            return dates;
        }
        List<DayOfWeek> dows = req.getDayOfWeeks();
        if (dows == null || dows.isEmpty()) {
            throw new BadRequestException(); // 주/4주 반복은 요일 1개 이상
        }
        int weeks = req.getMode() == RecurrenceMode.FOUR_WEEKS ? 4 : 1;
        LocalDate monday = req.getDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int w = 0; w < weeks; w++) {
            LocalDate weekStart = monday.plusWeeks(w);
            for (DayOfWeek dow : new LinkedHashSet<>(dows)) {
                LocalDate d = weekStart.plusDays(dow.getValue() - 1L);
                if (!d.isBefore(req.getDate())) {
                    dates.add(d);
                }
            }
        }
        return dates;
    }

    /* ─── 조회 ─────────────────────────────────────────────── */

    /** 캘린더 읽기 — [from, to] 범위(일/주/월 뷰는 FE 가 범위로 표현). venueName 은 배치 해석(N+1 회피). */
    public List<AvailabilityWindowResponse> list(Account instructor, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException();
        }
        List<AvailabilityWindow> windows =
                windowRepo.findByInstructorIdAndDateBetweenOrderByDateAscStartTimeAsc(instructor.getId(), from, to);
        Map<String, String> nameByRef = resolveNames(windows);
        Map<Long, List<Enrollment>> activeByWindow = activeByWindow(
                windows.stream().map(AvailabilityWindow::getId).collect(Collectors.toList()));
        return windows.stream()
                .map(w -> toResponse(w, nameByRef, activeByWindow))
                .collect(Collectors.toList());
    }

    public AvailabilityWindowResponse getMine(Account instructor, Long id) {
        return toResponse(requireOwned(instructor, id));
    }

    /* ─── 수정/삭제 ────────────────────────────────────────── */

    @Transactional
    public AvailabilityWindowResponse update(Account instructor, Long id, AvailabilityUpdateRequest req) {
        AvailabilityWindow w = requireOwned(instructor, id);
        requireValidRange(req.getStartTime(), req.getEndTime());
        requireValidCapacity(req.getCapacity());
        if (req.getCapacity() < w.heldCount()) {
            throw new BadRequestException(); // 정원을 현재 점유보다 낮출 수 없다
        }
        validateVenueRefIfPresent(instructor, req.getVenueRefId());

        w.setDate(req.getDate());
        w.setStartTime(req.getStartTime());
        w.setEndTime(req.getEndTime());
        w.setCapacity(req.getCapacity());
        w.setVenueRefId(StringUtils.hasText(req.getVenueRefId()) ? req.getVenueRefId().trim() : null);
        w.setSessionLabel(StringUtils.hasText(req.getSessionLabel()) ? req.getSessionLabel().trim() : null);
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    @Transactional
    public void delete(Account instructor, Long id) {
        windowRepo.delete(requireOwned(instructor, id));
    }

    /* ─── 점유 hold ─────────────────────────────────────────── */

    /** 점유 추가(외부예약 / ± 빠른조정). 정원 초과 시 정원 자동 확장(= 점유 합). */
    @Transactional
    public AvailabilityWindowResponse addHold(Account instructor, Long id, HoldRequest req) {
        AvailabilityWindow w = requireOwned(instructor, id);
        if (req.getCount() < 1) {
            throw new BadRequestException();
        }
        w.addHold(AvailabilityHold.builder()
                .count(req.getCount())
                .memo(StringUtils.hasText(req.getMemo()) ? req.getMemo().trim() : null)
                .createdAt(LocalDateTime.now())
                .build());
        if (w.heldCount() > w.getCapacity()) {
            w.setCapacity(w.heldCount()); // 정원 초과 시 자동 확장
        }
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    /** 점유 제거(± −1 / 외부예약 취소). 정원은 자동 축소하지 않는다(확장은 흔적 — 강사가 직접 줄임). */
    @Transactional
    public AvailabilityWindowResponse removeHold(Account instructor, Long id, Long holdId) {
        AvailabilityWindow w = requireOwned(instructor, id);
        boolean removed = w.getHolds().removeIf(h -> h.getId().equals(holdId));
        if (!removed) {
            throw new ResourceNotFoundException();
        }
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    /* ─── 파생 + 매핑 ──────────────────────────────────────── */

    /**
     * 5상태 파생 — v1 은 enrollment 가 없어 confirmed/pending = 0, 모든 점유는 외부 hold.
     * 미래 enrollment 가 붙으면 confirmed/pending 만 채우면 그대로 동작한다.
     */
    SlotStatus deriveStatus(AvailabilityWindow w, int confirmed, int pending) {
        int external = w.heldCount();
        int filled = confirmed + external;
        if (pending > 0 && filled == 0) {
            return SlotStatus.PENDING;
        }
        if (filled == 0) {
            return SlotStatus.AVAILABLE;
        }
        if (filled >= w.getCapacity()) {
            return SlotStatus.FULL;
        }
        if (external > 0) {
            return SlotStatus.EXTERNAL;
        }
        return SlotStatus.CONFIRMED;
    }

    private AvailabilityWindowResponse toResponse(AvailabilityWindow w) {
        return toResponse(w, resolveNames(List.of(w)), activeByWindow(List.of(w.getId())));
    }

    private AvailabilityWindowResponse toResponse(AvailabilityWindow w, Map<String, String> nameByRef,
                                                  Map<Long, List<Enrollment>> activeByWindow) {
        List<Enrollment> active = activeByWindow.getOrDefault(w.getId(), List.of());
        int confirmed = (int) active.stream().filter(e -> e.getStatus() == EnrollmentStatus.CONFIRMED).count();
        int pending = (int) active.stream().filter(e -> e.getStatus() == EnrollmentStatus.PENDING).count();
        int external = w.heldCount();
        SlotStatus status = deriveStatus(w, confirmed, pending);
        String venueName = w.getVenueRefId() == null ? null : nameByRef.get(w.getVenueRefId());
        List<ApplicantSummaryResponse> applicants = active.stream()
                .map(AvailabilityService::toApplicant).collect(Collectors.toList());
        return AvailabilityWindowResponse.of(
                w, status, confirmed + external, confirmed, external, pending, venueName, applicants);
    }

    /** 여러 window 의 활성(PENDING/CONFIRMED) enrollment 일괄 — 캘린더 점유·신청자 집계, N+1 회피. */
    private Map<Long, List<Enrollment>> activeByWindow(Collection<Long> windowIds) {
        if (windowIds.isEmpty()) {
            return Map.of();
        }
        return enrollmentRepo.findByAvailabilityWindowIdInAndStatusIn(
                        windowIds, List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED))
                .stream().collect(Collectors.groupingBy(e -> e.getAvailabilityWindow().getId()));
    }

    /** enrollment → 슬롯 안 학생 요약(이름·단체레벨·대여장비). 디자인의 SlotApplicantRow 와 통일. */
    private static ApplicantSummaryResponse toApplicant(Enrollment e) {
        String courseTag = e.getCourse() == null ? null
                : StringUtils.hasText(e.getCourse().getOrganizationCode())
                        ? e.getCourse().getOrganizationCode() : e.getCourse().getDisciplineCode();
        return ApplicantSummaryResponse.builder()
                .name(e.getStudent() == null ? null : e.getStudent().getNickName())
                .courseTag(courseTag)
                .gear(e.getEquipment().stream().map(EnrollmentEquipment::getName).collect(Collectors.toList()))
                .kind(null)
                .build();
    }

    /** window 들의 venueRefId 를 한 번에 표시명으로 해석(N+1 회피). 미지정/미존재는 빠진다. */
    private Map<String, String> resolveNames(List<AvailabilityWindow> windows) {
        Set<String> refs = windows.stream()
                .map(AvailabilityWindow::getVenueRefId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (refs.isEmpty()) {
            return Map.of();
        }
        return venueRefResolver.resolveAll(refs).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));
    }

    /* ─── 게이트 + 검증 ────────────────────────────────────── */

    /** 가용시간 진입 게이트 — 강사신청 보유(상태 무관). 미신청이면 강사 트랙 밖이라 400. */
    private void requireInstructorTrack(Account instructor) {
        if (!applicationRepo.existsByAccountId(instructor.getId())) {
            throw new BadRequestException();
        }
    }

    private void validateVenueRefIfPresent(Account me, String venueRefId) {
        if (StringUtils.hasText(venueRefId)) {
            venueRefValidator.validate(me, venueRefId.trim()); // 형식·소유/존재 검증(없으면 400)
        }
    }

    private void requireValidRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BadRequestException();
        }
    }

    private void requireValidCapacity(int capacity) {
        if (capacity < 1) {
            throw new BadRequestException();
        }
    }

    private AvailabilityWindow requireOwned(Account me, Long id) {
        AvailabilityWindow w = windowRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (w.getInstructor() == null || !w.getInstructor().getId().equals(me.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 일정 — 존재 숨김
        }
        return w;
    }
}
