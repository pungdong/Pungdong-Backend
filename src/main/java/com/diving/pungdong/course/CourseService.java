package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.CourseRoundInUseException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.persistence.PageClamp;
import com.diving.pungdong.course.dto.CourseBrowseCondition;
import com.diving.pungdong.course.dto.CourseCardResponse;
import com.diving.pungdong.course.dto.CourseCreateRequest;
import com.diving.pungdong.course.dto.CourseDetailResponse;
import com.diving.pungdong.course.dto.CourseResponse;
import com.diving.pungdong.venue.Region;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.venue.VenueRefResolver;
import com.diving.pungdong.venue.VenueRefValidator;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 코스 생성/조회/수정/상태전이 + 검증. 위치는 {@code venueRefId} 로 참조(코스 빌더 카탈로그로 검증),
 * 위치별 장비는 강사×위치 가격표에서 읽기 시점 합성(저장 안 함). 없음/비소유는 400(venue 컨벤션).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseJpaRepo courseRepo;
    /** 카드·상세의 저장 수/내 저장 여부. 토글 자체는 {@link CourseBookmarkService} 가 쓴다. */
    private final CourseBookmarkJpaRepo bookmarkRepo;
    private final DisciplineService disciplineService;
    private final VenueRefValidator venueRefValidator;
    private final VenueRefResolver venueRefResolver;
    private final VenueEquipmentService equipmentService;
    private final SiteSettingsProvider siteSettings;
    /** 공개 상세의 강사 카드 합성. 구현은 {@code branding} 에 있다 — {@link InstructorSummaryProvider} 참고. */
    private final InstructorSummaryProvider instructorSummaryProvider;
    /** 공개·판매는 그 종목 승인 강사만. 준비(생성·수정·일정)는 게이트하지 않는다. */
    private final InstructorApprovalPolicy instructorApprovalPolicy;
    /** 회차를 지워도 되는지 수강 쪽에 묻는 seam. 구현은 {@code enrollment} 에 있다 — {@link CourseRoundUsageProbe} 참고. */
    private final CourseRoundUsageProbe roundUsageProbe;

    @Transactional
    public CourseResponse create(Account me, CourseCreateRequest req) {
        Course course = Course.builder().instructor(me).status(CourseStatus.DRAFT)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        apply(me, course, req);
        Course saved = courseRepo.save(course);
        return CourseResponse.from(saved, equipmentMap(me, saved));
    }

    /**
     * 코스 수정 — 스칼라·미디어는 전량 교체, <b>회차는 재사용</b>한다.
     *
     * <p><b>왜 회차만 다른가.</b> {@code enrollment_round} 가 {@code course_round} 를 FK 로 참조한다. 예전
     * 구현은 수정할 때마다 회차를 통째로 지우고 다시 만들었는데, 수강생이 하나라도 있으면 그 삭제가 참조
     * 무결성 위반으로 <b>500</b> 을 냈다(제목만 바꿔도 터졌다). 이제 (종류, 회차번호)로 기존 행을 찾아
     * <b>내용만 갱신</b>하므로 회차 id 가 보존되고, 수강 기록이 그대로 살아 있다.
     *
     * <p>진짜로 사라지는 회차(회차 수 축소·추가세션 제거)만 삭제하고, 그중 수강 기록이 물린 게 있으면
     * {@link CourseRoundInUseException}(-1024) 으로 <b>거절</b>한다 — 남의 예약·결제를 BE 가 임의로 정리하지 않는다.
     */
    @Transactional
    public CourseResponse update(Account me, Long id, CourseCreateRequest req) {
        Course course = requireOwned(me, id);
        applyScalars(course, req);
        applyMedia(course, req);
        reconcileRounds(me, course, req);
        applyFacets(course);
        course.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    public CourseResponse get(Account me, Long id) {
        Course course = requireOwned(me, id);
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    /**
     * 공개 강의 상세 — 둘러보기 카드 → 상세(OPEN 코스 누구나). 강사용 {@link #get} 과 달리 venue 를 합성:
     * 위치 이름·type·주소, <b>입장료(이용권×daypart fee)</b>, 위치별 장비. 비OPEN/없음은 400(존재 숨김).
     */
    public CourseDetailResponse publicDetail(Long id, Account viewer) {
        Course course = requirePubliclyVisible(id);
        List<String> refs = course.getRounds().stream()
                .flatMap(r -> r.getVenues().stream())
                .map(RoundVenue::getVenueRefId)
                .collect(Collectors.toList());
        Map<String, com.diving.pungdong.venue.dto.VenueResponse> venueByRef = venueRefResolver.resolveVenues(refs);
        // 장비는 강사×위치 가격표 — 공개 상세도 그 코스 강사의 가격표를 합성.
        Map<String, VenueEquipmentResponse> equipByRef = equipmentMap(course.getInstructor(), course);
        return CourseDetailResponse.from(course, venueByRef, equipByRef,
                instructorSummaryProvider.summarize(course.getInstructor()),
                bookmarkRepo.countByCourseId(id),
                viewer != null && bookmarkRepo.findByCourseIdAndAccountId(id, viewer.getId()).isPresent());
    }

    /**
     * <b>공개 표면에 실제로 노출되는 강의</b>만 통과시킨다 — 공개 상세와 <b>저장(북마크)</b>이 같은 이
     * 게이트를 쓴다. 둘이 각자 조건을 들고 있으면 한쪽만 조여져 다른 쪽이 우회로가 된다(그 실수를 이
     * 도메인은 이미 두 번 했다 — 차단 강의와 미승인 강사가 상세 URL 로 새어 나갔다).
     *
     * <p>없음·비OPEN·차단·데모가림·미승인 강사는 모두 <b>같은 응답</b>(400, 존재 숨김)이다. 왜가 갈리면
     * 그 자체로 존재를 알려주는 채널이 된다.
     */
    public Course requirePubliclyVisible(Long id) {
        boolean showSeeded = siteSettings.current().showSeededCourses();
        return courseRepo.findById(id)
                .filter(c -> c.getStatus() == CourseStatus.OPEN)
                .filter(c -> !c.isBlocked()) // 어드민 조치 — 둘러보기에서만 빼면 상세 URL 이 우회로가 된다
                .filter(c -> showSeeded || !c.isSeeded()) // 데모 가림 시 상세도 숨김(존재 숨김)
                // 승인 전(또는 반려된) 강사의 강의 — 둘러보기에서만 빼면 이 URL 이 우회로가 된다.
                .filter(instructorApprovalPolicy::isApproved)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** 내 강의 목록 — 카드용. 위치별 장비 합성은 상세에서만(목록은 빈 맵). */
    public List<CourseResponse> listMine(Account me) {
        return courseRepo.findAllByInstructorIdOrderByIdDesc(me.getId()).stream()
                .map(c -> CourseResponse.from(c, Collections.emptyMap()))
                .collect(Collectors.toList());
    }

    /**
     * 공개 둘러보기 — OPEN 코스만, 종목/지역/레벨·종류/단체/가격 필터 + 정렬. 빈 결과는 예외 아니라
     * 빈 페이지(repo 규약: 음성 결과는 200). 지역 필터는 저장 시점 비정규화된 {@code regions} 컬럼으로,
     * 정렬은 클라이언트 임의 필드가 아니라 {@link CourseBrowseCondition.Sort} 화이트리스트만 허용.
     *
     * <p><b>{@link PageClamp} 를 먼저 통과시킨다</b> — 여기엔 size 상한이 없어 {@code ?size=100000} 으로
     * 카탈로그를 통째로 긁을 수 있었다(어드민 신고 큐에서 실제로 났던 사고와 같은 구멍). 클라이언트가
     * 보낸 {@code sort=price,desc} 형태의 Pageable 정렬도 여기서 버려진다 — 아래 {@link #sortOf} 가
     * 재구성하므로 예전에도 결과에 영향은 없었지만, "버린다"는 의도가 코드에 남아 있지 않았다.
     *
     * <p>🔴 <b>돌려주는 {@code Page} 의 정렬은 다시 벗겨서 내보낸다.</b> {@code PagedResourcesAssembler} 는
     * {@code Page.getPageable().getSort()} 를 HAL 링크에 그대로 직렬화하는데, 그러면
     * {@code _links.self}/{@code next} 가 {@code ?sort=createdAt,id,desc} 를 달고 나간다. 그런데 이
     * 엔드포인트의 {@code sort} 파라미터는 {@link CourseBrowseCondition.Sort} enum 이라 그 값이 되돌아오면
     * <b>enum 변환 실패로 400</b> 이다 — 즉 서버가 스스로 만든 "다음 페이지" 링크를 따라가면 깨진다.
     * 무한 스크롤 클라이언트가 {@code _links.next} 를 쓰기 시작하는 순간 터지므로 여기서 끊는다.
     * ({@code /instructors/public} 은 Sort 를 다시 싣지 않아 원래 이 문제가 없다.)
     */
    public Page<CourseCardResponse> browse(CourseBrowseCondition condition, Account viewer, Pageable pageable) {
        Pageable fixed = PageClamp.fixed(pageable);

        // "저장한 강의" 는 로그인해야 의미가 있다. 비로그인은 에러가 아니라 빈 페이지가 맞는 답이다 —
        // 로그인 안 한 사람에게 저장한 강의가 없는 건 정상 상태지 실패가 아니다(레포 규칙, 커뮤니티 동일).
        if (condition.isBookmarkedByMe() && viewer == null) {
            return Page.empty(fixed);
        }

        PageRequest request = PageRequest.of(
                fixed.getPageNumber(), fixed.getPageSize(), sortOf(condition.getSort()));
        Specification<Course> spec = CourseSpecifications.matching(condition);
        if (!siteSettings.current().showSeededCourses()) {
            spec = spec.and(CourseSpecifications.excludeSeeded()); // 런칭 후 데모 가림
        }
        if (condition.isBookmarkedByMe()) {
            spec = spec.and(CourseSpecifications.bookmarkedBy(viewer.getId()));
        }
        Page<Course> courses = courseRepo.findAll(spec, request);
        return new PageImpl<>(
                courses.map(cardMapperFor(courses.getContent(), viewer)).getContent(),
                PageRequest.of(request.getPageNumber(), request.getPageSize()), courses.getTotalElements());
    }

    /**
     * 카드의 저장 상태·저장 수를 <b>페이지 단위 일괄 조회</b>로 채운다 — 카드마다 세면 N+1 이다.
     * 추가 비용은 페이지당 최대 2쿼리(집계 1 + 내 저장 1)이고, 비로그인은 1쿼리다(내 저장을 안 읽는다).
     *
     * <p>{@code bookmarkedByMe} 는 <b>토큰이 있을 때만 의미가 있다.</b> 공개 둘러보기라 비로그인이면
     * 에러가 아니라 조용히 전부 false 로 온다 — FE 가 토큰리스로 캐시 경로를 타면 같은 일이 벌어진다
     * (커뮤니티에서 이미 겪은 함정이라 {@code types.ts} 에 명시해 뒀다).
     */
    private java.util.function.Function<Course, CourseCardResponse> cardMapperFor(List<Course> courses,
                                                                                 Account viewer) {
        List<Long> ids = courses.stream().map(Course::getId).collect(Collectors.toList());
        Map<Long, Long> counts = new HashMap<>();
        Set<Long> mine = new java.util.HashSet<>();
        if (!ids.isEmpty()) {
            for (Object[] row : bookmarkRepo.countByCourseIds(ids)) {
                counts.put((Long) row[0], (Long) row[1]);
            }
            if (viewer != null) {
                mine.addAll(bookmarkRepo.findBookmarkedCourseIds(viewer.getId(), ids));
            }
        }
        return c -> CourseCardResponse.from(c,
                counts.getOrDefault(c.getId(), 0L), mine.contains(c.getId()));
    }

    private Sort sortOf(CourseBrowseCondition.Sort sort) {
        CourseBrowseCondition.Sort s = sort == null ? CourseBrowseCondition.Sort.LATEST : sort;
        switch (s) {
            case PRICE_ASC:
                return Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id"));
            case PRICE_DESC:
                return Sort.by(Sort.Order.desc("price"), Sort.Order.desc("id"));
            case LATEST:
            default:
                return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }
    }

    /**
     * 상태 전이. <b>OPEN(발행) 만 승인 게이트가 붙는다</b> — DRAFT/CLOSED 로 내리는 건 언제든 된다.
     * 승인 전 강사가 강의를 만들어 두는 것 자체는 막지 않는다({@link InstructorApprovalPolicy} 참고).
     */
    @Transactional
    public CourseResponse updateStatus(Account me, Long id, CourseStatus status) {
        Course course = requireOwned(me, id);
        if (status == CourseStatus.OPEN) {
            instructorApprovalPolicy.requireApprovedToPublish(course);
        }
        course.setStatus(status);
        course.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    /* ─── 적용(스칼라·미디어는 교체 / 회차는 재사용) ─────────────────────── */

    private void apply(Account me, Course course, CourseCreateRequest req) {
        applyScalars(course, req);
        applyMedia(course, req);
        reconcileRounds(me, course, req);
        applyFacets(course);
    }

    /** 기본정보 — 생성·수정 공통. */
    private void applyScalars(Course course, CourseCreateRequest req) {
        if (!StringUtils.hasText(req.getTitle())) {
            throw new BadRequestException();
        }
        disciplineService.getActiveByCode(req.getDisciplineCode()); // 없거나 비활성 → 예외

        course.setTitle(req.getTitle().trim());
        course.setKind(req.getKind());
        course.setDisciplineCode(req.getDisciplineCode());
        course.setPrice(req.getPrice());
        course.setTotalRounds(req.getTotalRounds());
        course.setDescription(req.getDescription());
        applyKind(course, req);
    }

    /** 미디어 — 전량 교체. 참조자가 없어 지웠다 다시 만들어도 안전하다(회차와 다른 점). */
    private void applyMedia(Course course, CourseCreateRequest req) {
        course.clearMedia();
        List<CourseCreateRequest.Media> media = req.getMedia() == null ? List.of() : req.getMedia();
        for (int i = 0; i < media.size(); i++) {
            CourseCreateRequest.Media m = media.get(i);
            course.addMedia(CourseMedia.builder().kind(m.getKind()).url(m.getUrl()).sortOrder(i).build());
        }
    }

    /**
     * 회차 맞추기 — <b>(종류, 회차번호)로 기존 행을 찾아 재사용</b>하고, 없으면 만들고, 요청에서 사라진 것만 지운다.
     *
     * <p>생성 경로에선 기존 회차가 없으니 전부 "만들고" 지날 뿐이라 결과가 예전과 같다. 수정 경로에선 이
     * 재사용이 핵심이다 — 회차 id 가 보존돼야 {@code enrollment_round} 의 FK 가 살아남는다.
     *
     * <p>회차 <b>내부</b>의 위치·이용권({@code RoundVenue})은 그대로 전량 교체한다. 그걸 FK 로 참조하는
     * 테이블은 없다(수강은 위치를 {@code venueRefId} 문자열로 <b>스냅샷</b>해 둔다) — 그래서 강사가 위치를
     * 바꿔도 <b>이미 확정된 학생의 예약은 움직이지 않고</b>, 앞으로 잡을 회차의 후보만 바뀐다.
     */
    private void reconcileRounds(Account me, Course course, CourseCreateRequest req) {
        List<CourseCreateRequest.Round> reqRounds = req.getRounds() == null ? List.of() : req.getRounds();
        if (reqRounds.size() != req.getTotalRounds()) {
            throw new BadRequestException();
        }

        List<CourseRound> before = new ArrayList<>(course.getRounds());
        Map<Integer, CourseRound> regularByIndex = before.stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.getRoundIndex() != null)
                .collect(Collectors.toMap(CourseRound::getRoundIndex, r -> r, (a, b) -> a));
        CourseRound existingExtra = before.stream()
                .filter(r -> r.getRoundKind() == RoundKind.EXTRA).findFirst().orElse(null);

        // 살아남는 회차는 동일성으로 모은다 — 새 회차는 id 가 전부 null 이라 equals 로 묶으면 서로 겹친다.
        Set<CourseRound> survivors = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int i = 0; i < reqRounds.size(); i++) {
            CourseCreateRequest.Round r = reqRounds.get(i);
            int index = i + 1;
            CourseRound round = regularByIndex.get(index);
            if (round == null) {
                round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(index).build();
                course.addRound(round);
            }
            round.setPlatformConfirmed(index == 1); // 1회차(첫 만남) = 플랫폼 확정
            round.setDescription(r.getDescription());
            replaceVenues(me, round, r.getVenues());
            survivors.add(round);
        }

        // 추가세션(선택) = EXTRA 회차 + 비용 정책
        if (req.getExtraSession() != null) {
            CourseCreateRequest.ExtraSession ex = req.getExtraSession();
            CourseRound extra = existingExtra;
            if (extra == null) {
                extra = CourseRound.builder().roundKind(RoundKind.EXTRA).build();
                course.addRound(extra);
            }
            extra.setPlatformConfirmed(false);
            extra.setDescription(ex.getDescription());
            extra.setFreeCount(ex.getFreeCount());
            extra.setPerSessionPrice(ex.getPerSessionPrice());
            replaceVenues(me, extra, ex.getVenues());
            survivors.add(extra);
        }

        List<CourseRound> gone = before.stream()
                .filter(r -> !survivors.contains(r))
                .collect(Collectors.toList());
        if (!gone.isEmpty()) {
            requireNotEnrolled(gone);
            course.removeRounds(gone);
        }
        course.sortRounds();
    }

    /**
     * 사라지는 회차에 수강 기록이 물려 있으면 거절 — 그냥 두면 DB 가 참조 무결성 위반(500)으로 막는다.
     * <b>상태를 가리지 않는다</b>: 취소·거절된 수강도 행은 남아 있어 FK 는 그대로 걸린다.
     */
    private void requireNotEnrolled(List<CourseRound> gone) {
        List<Long> ids = gone.stream()
                .map(CourseRound::getId).filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (!roundUsageProbe.inUse(ids).isEmpty()) {
            throw new CourseRoundInUseException();
        }
    }

    /**
     * 둘러보기 비정규화 — 회차 위치들의 주소에서 지역 묶음 집합 + 대표 위치명(첫 위치)을 풀어 코스에 박는다
     * (cleared/replaced 스냅샷). 위치 해석은 {@link VenueRefResolver}(CUSTOM=DB, OFFICIAL=Sanity 캐시).
     */
    private void applyFacets(Course course) {
        List<String> refs = course.getRounds().stream()
                .flatMap(r -> r.getVenues().stream())
                .map(RoundVenue::getVenueRefId)
                .collect(Collectors.toList());
        Map<String, VenueRefResolver.Resolved> resolved = venueRefResolver.resolveAll(refs);

        Set<Region> regions = new LinkedHashSet<>();
        String primary = null;
        for (String ref : refs) {
            VenueRefResolver.Resolved r = resolved.get(ref);
            if (r == null) {
                continue;
            }
            regions.add(r.getRegion());
            if (primary == null) {
                primary = r.getName();
            }
        }
        course.setRegions(regions);
        course.setPrimaryLocationName(primary);
    }

    private void applyKind(Course course, CourseCreateRequest req) {
        course.getLevels().clear();
        if (req.getKind() == CourseKind.CERTIFICATION) {
            if (CollectionUtils.isEmpty(req.getLevels()) || !StringUtils.hasText(req.getOrganizationCode())) {
                throw new BadRequestException(); // 자격 과정은 단체 + 레벨 필수
            }
            course.setOrganizationCode(req.getOrganizationCode());
            course.setLevels(new LinkedHashSet<>(req.getLevels()));
        } else {
            // TRIAL/TRAINING — 자격 아님: 단체/레벨 무시(비움)
            course.setOrganizationCode(null);
        }
    }

    /**
     * 회차의 위치·이용권을 전량 교체. 재사용된 회차라도 이 자식들은 지웠다 다시 만든다 — 참조자가 없어
     * 안전하다(수강은 위치를 문자열로 스냅샷해 둔다).
     */
    private void replaceVenues(Account me, CourseRound round, List<CourseCreateRequest.Venue> venues) {
        round.getVenues().clear();
        List<CourseCreateRequest.Venue> list = venues == null ? List.of() : venues;
        for (int i = 0; i < list.size(); i++) {
            CourseCreateRequest.Venue v = list.get(i);
            venueRefValidator.validate(me, v.getVenueRefId()); // CUSTOM 내 소유 / OFFICIAL 캐시 존재
            RoundVenue rv = RoundVenue.builder().venueRefId(v.getVenueRefId()).sortOrder(i).build();
            List<CourseCreateRequest.Ticket> tickets = v.getTickets() == null ? List.of() : v.getTickets();
            for (int j = 0; j < tickets.size(); j++) {
                CourseCreateRequest.Ticket t = tickets.get(j);
                rv.addTicket(RoundVenueTicket.builder()
                        .ticketRef(t.getTicketRef()).daypart(t.getDaypart()).sortOrder(j).build());
            }
            round.addVenue(rv);
        }
    }

    private Course requireOwned(Account me, Long id) {
        Course course = courseRepo.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (course.getInstructor() == null || !course.getInstructor().getId().equals(me.getId())) {
            throw new ResourceNotFoundException(); // 남의 코스 존재 숨김 (400)
        }
        return course;
    }

    /** 코스의 모든 위치 참조에 대해 내 장비 가격표를 모아 합성용 맵 구성. */
    private Map<String, VenueEquipmentResponse> equipmentMap(Account me, Course course) {
        Set<String> refs = course.getRounds().stream()
                .flatMap(r -> r.getVenues().stream())
                .map(RoundVenue::getVenueRefId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, VenueEquipmentResponse> map = new HashMap<>();
        for (String ref : refs) {
            equipmentService.findMine(me, ref).ifPresent(e -> map.put(ref, e));
        }
        return map;
    }
}
