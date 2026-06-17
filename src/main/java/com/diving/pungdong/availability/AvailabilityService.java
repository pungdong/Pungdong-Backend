package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.availability.dto.AvailabilityCreateRequest;
import com.diving.pungdong.availability.dto.AvailabilitySettingsResponse;
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
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final VenueRefValidator venueRefValidator;
    private final VenueRefResolver venueRefResolver;
    private final EnrollmentJpaRepo enrollmentRepo;

    /* ─── 생성 (반복 전개) ─────────────────────────────────── */

    @Transactional
    public List<AvailabilityWindowResponse> create(Account instructor, AvailabilityCreateRequest req) {
        requireInstructorTrack(instructor);
        requireValidRange(req.getStartTime(), req.getEndTime());
        requireValidOverride(req.getCapacity()); // null = 계정 기본값 따름, 주면 1 이상
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
                        .capacityOverride(req.getCapacity()) // null = 계정 기본값 라이브 참조
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
        validateVenueRefIfPresent(instructor, req.getVenueRefId());

        w.setDate(req.getDate());
        w.setStartTime(req.getStartTime());
        w.setEndTime(req.getEndTime());
        // 정원은 여기서 안 건드린다 — setWindowCapacity/resetWindowCapacity 전용(시간 수정과 분리).
        w.setVenueRefId(StringUtils.hasText(req.getVenueRefId()) ? req.getVenueRefId().trim() : null);
        w.setSessionLabel(StringUtils.hasText(req.getSessionLabel()) ? req.getSessionLabel().trim() : null);
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    /* ─── 정원 — 계정 기본값(baseline) + 일정 override ────────── */

    /** 일정탭 상단 ± 가 읽는 현재 기본 정원. 게이트 = 강사신청 보유. */
    public AvailabilitySettingsResponse getSettings(Account instructor) {
        requireInstructorTrack(instructor);
        return AvailabilitySettingsResponse.builder()
                .defaultCapacity(instructor.effectiveDefaultCapacity())
                .build();
    }

    /**
     * 계정 기본 정원(baseline) 조정 — 일정탭 ±. override 없는 일정들은 이 값을 라이브로 따른다(전파 write 없음).
     * 이미 확정된 점유는 영향 없음(취소 없음, 만석 체크가 바닥). 1 이상.
     */
    @Transactional
    public AvailabilitySettingsResponse updateDefaultCapacity(Account instructor, Integer capacity) {
        requireInstructorTrack(instructor);
        requirePositive(capacity);
        instructor.setDefaultCapacity(capacity);
        accountRepo.save(instructor);
        return AvailabilitySettingsResponse.builder().defaultCapacity(capacity).build();
    }

    /** 그 일정만 정원 고정(override 설정/변경) — 일정 카드 ±. 1 이상. 점유보다 낮춰도 허용(확정자 유지). */
    @Transactional
    public AvailabilityWindowResponse setWindowCapacity(Account instructor, Long id, Integer capacity) {
        AvailabilityWindow w = requireOwned(instructor, id);
        requirePositive(capacity);
        w.setCapacityOverride(capacity);
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    /** 일정 override 해제 — "기본값 따르기". 이후 계정 기본값을 라이브로 따른다. */
    @Transactional
    public AvailabilityWindowResponse resetWindowCapacity(Account instructor, Long id) {
        AvailabilityWindow w = requireOwned(instructor, id);
        w.setCapacityOverride(null);
        w.setUpdatedAt(LocalDateTime.now());
        return toResponse(w);
    }

    @Transactional
    public void delete(Account instructor, Long id) {
        windowRepo.delete(requireOwned(instructor, id));
    }

    /* ─── 점유 hold ─────────────────────────────────────────── */

    /**
     * 점유 추가(외부예약 / ± 빠른조정). 외부 reality 는 항상 기록 가능 — 유효정원을 넘겨도 막지 않는다(점유가
     * 정원을 초과하면 그냥 FULL, 추가 풍덩 신청만 차단). 옛 "정원 자동 확장"은 이 바닥 개념으로 흡수(저장값을
     * 안 올림 — 외부 hold 가 빠지면 자동 원복).
     */
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
        if (filled >= w.effectiveCapacity()) {
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

    /**
     * enrollment → 슬롯 안 학생 요약(이름·단체/종목/레벨·대여장비). 단체·레벨은 <b>평탄 3종</b>으로 내리고
     * (org/discipline/levels) 표시명 해석은 FE 가 Sanity cert 카탈로그로 한다 — cert 는 FE-direct CDN 이라
     * BE 는 단체별 명칭을 모른다([[sanity-read-principle]]). 디자인의 SlotApplicantRow 와 통일.
     */
    private static ApplicantSummaryResponse toApplicant(Enrollment e) {
        var course = e.getCourse();
        List<String> levels = course == null || course.getLevels() == null ? List.of()
                : course.getLevels().stream().sorted().map(Enum::name).collect(Collectors.toList());
        return ApplicantSummaryResponse.builder()
                .name(e.getStudent() == null ? null : e.getStudent().getNickName())
                .organizationCode(course == null ? null : course.getOrganizationCode())
                .disciplineCode(course == null ? null : course.getDisciplineCode())
                .levels(levels)
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

    /** 정원 값 검증 — 반드시 존재 + 1 이상(계정 기본값/일정 override 설정용). */
    private void requirePositive(Integer capacity) {
        if (capacity == null || capacity < 1) {
            throw new BadRequestException();
        }
    }

    /** 생성 시 정원 override 검증 — null 허용(계정 기본값 따름), 주면 1 이상. */
    private void requireValidOverride(Integer capacity) {
        if (capacity != null && capacity < 1) {
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
