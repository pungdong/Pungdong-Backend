package com.diving.pungdong.venue;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.venue.dto.VenueCreateRequest;
import com.diving.pungdong.venue.dto.VenueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강사 커스텀(CUSTOM) 위치 생성/조회/수정/삭제 + 검증.
 *
 * <p>공식(OFFICIAL) 위치는 BE 가 다루지 않는다 — Sanity authoring. BE 는 강사 본인 소유 커스텀 위치만.
 * 코스 빌더 official+custom 통합은 후속 BE 머지 엔드포인트가 official(Sanity 서버사이드 읽기)+custom(DB)을
 * 합쳐 반환(FE 소스 무지) — course 생성과 함께. 현재 {@code listMine} 은 내 custom 목록(관리용).
 *
 * <p>응답은 트랜잭션 안에서 {@link VenueResponse} 로 매핑(LAZY 자식 컬렉션 보호). 없음/비소유는
 * {@link ResourceNotFoundException}(레포 컨벤션상 400) 으로 통일 — 남의 커스텀 위치 존재를 숨긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueService {

    private final VenueJpaRepo venueRepo;
    private final DisciplineService disciplineService;
    private final InstructorApplicationJpaRepo applicationRepo;

    @Transactional
    public VenueResponse create(Account owner, VenueCreateRequest req) {
        String lockedCode = requireLockedDiscipline(req);
        requireInstructorTrack(owner, lockedCode);
        Venue venue = Venue.builder().owner(owner).createdAt(LocalDateTime.now()).build();
        apply(venue, req, lockedCode);
        return VenueResponse.from(venueRepo.save(venue));
    }

    /** 코스 빌더 소비용 — 내 커스텀 위치, 종목/유형으로 좁힘. (OFFICIAL 은 FE 가 Sanity 에서 합침.) */
    public List<VenueResponse> listMine(Account me, String disciplineCode, VenueType type) {
        return venueRepo.findAllByOwnerIdOrderByIdDesc(me.getId()).stream()
                .filter(v -> type == null || v.getType() == type)
                .filter(v -> !StringUtils.hasText(disciplineCode) || offersDiscipline(v, disciplineCode))
                .map(VenueResponse::from)
                .collect(Collectors.toList());
    }

    public VenueResponse getMine(Account me, Long id) {
        return VenueResponse.from(requireOwned(me, id));
    }

    @Transactional
    public VenueResponse update(Account me, Long id, VenueCreateRequest req) {
        Venue venue = requireOwned(me, id);
        String lockedCode = requireLockedDiscipline(req);
        apply(venue, req, lockedCode);
        venue.setUpdatedAt(LocalDateTime.now());
        return VenueResponse.from(venue);
    }

    @Transactional
    public void delete(Account me, Long id) {
        venueRepo.delete(requireOwned(me, id));
    }

    /* ─── 적용(스칼라 + 자식 전량 교체) ─────────────────────── */

    private void apply(Venue venue, VenueCreateRequest req, String lockedCode) {
        if (!StringUtils.hasText(req.getName())) {
            throw new BadRequestException();
        }
        venue.setName(req.getName().trim());
        venue.setType(req.getType());
        venue.setAddress(req.getAddress());
        venue.setAddressDetail(req.getAddressDetail());
        venue.setLatitude(req.getLatitude());
        venue.setLongitude(req.getLongitude());
        venue.setMaxDepth(req.getMaxDepth());
        venue.setLockedDisciplineCode(lockedCode);

        venue.clearChildren();
        if (req.getTickets() == null || req.getTickets().isEmpty()) {
            throw new BadRequestException(); // 이용 옵션 최소 1개
        }
        for (VenueCreateRequest.Ticket t : req.getTickets()) {
            venue.addTicket(buildTicket(t, lockedCode));
        }
        if (req.getClosures() != null) {
            for (VenueCreateRequest.Closure c : req.getClosures()) {
                venue.addClosure(buildClosure(c));
            }
        }
    }

    private VenueTicket buildTicket(VenueCreateRequest.Ticket t, String lockedCode) {
        VenueTicket ticket = VenueTicket.builder()
                .name(t.getName())
                .sortOrder(t.getSortOrder())
                .disciplineCodes(resolveDisciplines(t, lockedCode))
                .build();

        if (t.getDayparts() == null || t.getDayparts().isEmpty()) {
            throw new BadRequestException();
        }
        boolean hasWeekday = false;
        Set<DaypartKind> kinds = new LinkedHashSet<>();
        for (VenueCreateRequest.Daypart d : t.getDayparts()) {
            if (d.getKind() == null || !kinds.add(d.getKind())) {
                throw new BadRequestException(); // 같은 종류 daypart 중복 금지
            }
            if (d.getKind() == DaypartKind.WEEKDAY) {
                hasWeekday = true;
            }
            ticket.addDaypart(buildDaypart(d));
        }
        if (!hasWeekday) {
            throw new BadRequestException(); // 평일 daypart 필수
        }
        return ticket;
    }

    /** 커스텀은 lockedCode 1개로 강제 — 잠긴 종목과 다른 종목을 이용 옵션에 넣으면 400. */
    private Set<String> resolveDisciplines(VenueCreateRequest.Ticket t, String lockedCode) {
        List<String> provided = t.getDisciplineCodes();
        if (provided != null && provided.stream().anyMatch(c -> StringUtils.hasText(c) && !c.equals(lockedCode))) {
            throw new BadRequestException();
        }
        return new LinkedHashSet<>(List.of(lockedCode));
    }

    private VenueDaypart buildDaypart(VenueCreateRequest.Daypart d) {
        boolean weekend = d.getKind() == DaypartKind.WEEKEND;
        boolean sold = !weekend || d.isSold(); // 평일은 항상 판매

        VenueDaypart daypart = VenueDaypart.builder()
                .kind(d.getKind())
                .sold(sold)
                .fee(d.getFee())
                .timeMode(d.getTimeMode())
                .openStart(d.getOpenStart())
                .openEnd(d.getOpenEnd())
                .holdHours(d.getHoldHours())
                .build();

        if (!sold) {
            return daypart; // 주말 미판매 — 시간/요금 검증 생략
        }

        TimeMode mode = d.getTimeMode();
        if (mode == null) {
            throw new BadRequestException();
        }
        if (mode == TimeMode.SAME && !weekend) {
            throw new BadRequestException(); // SAME 은 주말 전용
        }
        if (d.getFee() == null || d.getFee() < 0) {
            throw new BadRequestException(); // 입장료 필수·음수 금지
        }

        if (mode == TimeMode.FIXED) {
            if (d.getTimeBlocks() == null || d.getTimeBlocks().isEmpty()) {
                throw new BadRequestException(); // 고정 시간대는 블록 1개 이상
            }
            for (VenueCreateRequest.TimeBlock b : d.getTimeBlocks()) {
                requireValidRange(b.getStartTime(), b.getEndTime());
                daypart.addTimeBlock(VenueTimeBlock.builder()
                        .startTime(b.getStartTime()).endTime(b.getEndTime()).sortOrder(b.getSortOrder()).build());
            }
        } else if (mode == TimeMode.OPEN) {
            requireValidRange(d.getOpenStart(), d.getOpenEnd());
            if (d.getHoldHours() == null || d.getHoldHours() <= 0) {
                throw new BadRequestException(); // 상시 입장은 키반납 시간 필수
            }
        }
        // SAME: 평일 구성을 따르므로 추가 검증 없음(가격만 다를 수 있음)
        return daypart;
    }

    private VenueClosure buildClosure(VenueCreateRequest.Closure c) {
        if (c.getType() == null) {
            throw new BadRequestException();
        }
        VenueClosure closure = VenueClosure.builder().type(c.getType()).build();
        if (c.getType() == ClosureType.WEEKLY) {
            if (c.getWeekdays() == null || c.getWeekdays().isEmpty()) {
                throw new BadRequestException(); // 매주 휴무는 요일 1개 이상
            }
            closure.setWeekdays(new LinkedHashSet<>(c.getWeekdays()));
        } else {
            // 월간은 atomic — "N째 주 X요일" 1건. 여러 주/요일은 MONTHLY 항목을 여러 개로.
            if (c.getNth() == null || c.getNth() < 1 || c.getNth() > 5 || c.getMonthlyWeekday() == null) {
                throw new BadRequestException();
            }
            closure.setNth(c.getNth());
            closure.setMonthlyWeekday(c.getMonthlyWeekday());
        }
        return closure;
    }

    /* ─── 공통 헬퍼 ──────────────────────────────────────────── */

    private void requireValidRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BadRequestException();
        }
    }

    private String requireLockedDiscipline(VenueCreateRequest req) {
        String code = req.getLockedDisciplineCode();
        if (!StringUtils.hasText(code)) {
            throw new BadRequestException(); // 커스텀 위치는 종목 잠금 필수
        }
        disciplineService.getActiveByCode(code); // 없거나 비활성이면 400
        return code;
    }

    /** 커스텀 위치 생성 게이트 — 그 종목 강사 신청 보유(상태 무관). 미신청이면 강사 트랙 밖이라 400. */
    private void requireInstructorTrack(Account owner, String disciplineCode) {
        if (!applicationRepo.existsByAccountIdAndDisciplineCode(owner.getId(), disciplineCode)) {
            throw new BadRequestException();
        }
    }

    private boolean offersDiscipline(Venue venue, String disciplineCode) {
        return venue.getTickets().stream()
                .anyMatch(t -> t.getDisciplineCodes().contains(disciplineCode));
    }

    private Venue requireOwned(Account me, Long id) {
        Venue venue = venueRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (venue.getOwner() == null || !venue.getOwner().getId().equals(me.getId())) {
            throw new ResourceNotFoundException(); // 없음/남의 커스텀 — 내 편집 대상 아님(존재 숨김)
        }
        return venue;
    }
}
