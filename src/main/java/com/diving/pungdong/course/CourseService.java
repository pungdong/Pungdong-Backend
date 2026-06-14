package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.course.dto.CourseCreateRequest;
import com.diving.pungdong.course.dto.CourseResponse;
import com.diving.pungdong.venue.VenueRefValidator;
import com.diving.pungdong.venue.equipment.VenueEquipmentService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
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
    private final DisciplineService disciplineService;
    private final VenueRefValidator venueRefValidator;
    private final VenueEquipmentService equipmentService;

    @Transactional
    public CourseResponse create(Account me, CourseCreateRequest req) {
        Course course = Course.builder().instructor(me).status(CourseStatus.DRAFT)
                .createdAt(LocalDateTime.now()).build();
        apply(me, course, req);
        Course saved = courseRepo.save(course);
        return CourseResponse.from(saved, equipmentMap(me, saved));
    }

    @Transactional
    public CourseResponse update(Account me, Long id, CourseCreateRequest req) {
        Course course = requireOwned(me, id);
        course.clearChildren();
        apply(me, course, req);
        course.setUpdatedAt(LocalDateTime.now());
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    public CourseResponse get(Account me, Long id) {
        Course course = requireOwned(me, id);
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    /** 내 강의 목록 — 카드용. 위치별 장비 합성은 상세에서만(목록은 빈 맵). */
    public List<CourseResponse> listMine(Account me) {
        return courseRepo.findAllByInstructorIdOrderByIdDesc(me.getId()).stream()
                .map(c -> CourseResponse.from(c, Collections.emptyMap()))
                .collect(Collectors.toList());
    }

    @Transactional
    public CourseResponse updateStatus(Account me, Long id, CourseStatus status) {
        Course course = requireOwned(me, id);
        course.setStatus(status);
        course.setUpdatedAt(LocalDateTime.now());
        return CourseResponse.from(course, equipmentMap(me, course));
    }

    /* ─── 적용(스칼라 + 자식 전량 교체) ─────────────────────── */

    private void apply(Account me, Course course, CourseCreateRequest req) {
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

        // 미디어
        List<CourseCreateRequest.Media> media = req.getMedia() == null ? List.of() : req.getMedia();
        for (int i = 0; i < media.size(); i++) {
            CourseCreateRequest.Media m = media.get(i);
            course.addMedia(CourseMedia.builder().kind(m.getKind()).url(m.getUrl()).sortOrder(i).build());
        }

        // 정규 회차 — 개수가 totalRounds 와 일치해야
        List<CourseCreateRequest.Round> rounds = req.getRounds() == null ? List.of() : req.getRounds();
        if (rounds.size() != req.getTotalRounds()) {
            throw new BadRequestException();
        }
        for (int i = 0; i < rounds.size(); i++) {
            CourseCreateRequest.Round r = rounds.get(i);
            CourseRound round = CourseRound.builder()
                    .roundKind(RoundKind.REGULAR)
                    .roundIndex(i + 1)
                    .platformConfirmed(i == 0) // 1회차(첫 만남) = 플랫폼 확정
                    .description(r.getDescription())
                    .build();
            addVenues(me, round, r.getVenues());
            course.addRound(round);
        }

        // 추가세션(선택) = EXTRA 회차 + 비용 정책
        if (req.getExtraSession() != null) {
            CourseCreateRequest.ExtraSession ex = req.getExtraSession();
            CourseRound extra = CourseRound.builder()
                    .roundKind(RoundKind.EXTRA)
                    .platformConfirmed(false)
                    .description(ex.getDescription())
                    .freeCount(ex.getFreeCount())
                    .perSessionPrice(ex.getPerSessionPrice())
                    .build();
            addVenues(me, extra, ex.getVenues());
            course.addRound(extra);
        }
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

    private void addVenues(Account me, CourseRound round, List<CourseCreateRequest.Venue> venues) {
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
