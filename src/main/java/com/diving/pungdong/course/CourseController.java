package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.course.dto.CourseBrowseCondition;
import com.diving.pungdong.course.dto.CourseCardResponse;
import com.diving.pungdong.course.dto.CourseCreateRequest;
import com.diving.pungdong.course.dto.CourseResponse;
import com.diving.pungdong.course.dto.CourseStatusRequest;
import com.diving.pungdong.course.dto.LevelLabelResponse;
import com.diving.pungdong.venue.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 강사 코스 작성/관리. 위치는 {@code venueRefId}(코스 빌더 목록), 위치별 장비는 강사×위치 가격표에서
 * 합성, 사진은 {@code POST /course-images} 로 선업로드한 url.
 *
 * <p>매처 {@code /courses/**} → authenticated (강사 트랙; 리뷰 대기 STUDENT 도 draft 준비 — venue 동일).
 * 단 {@code GET /courses/browse} 만 공개(permitAll) — 수강생 메인 홈/둘러보기. PII 없음 → GET 무방.
 */
@RestController
@RequestMapping(value = "/courses", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /**
     * 종목별 자격 레벨 표시 라벨(공개) — 수강생 둘러보기 필터 칩용. 평탄화 코드 + 종목 통용 명칭(스쿠버
     * "Open Water Diver" 등, 프리다이빙은 alias 없음). 강사 작성 화면은 단체 골랐으니 이걸 안 쓰고 Sanity
     * displayName 병기({@link LevelLabelResponse} 참고). disciplineCode 필수(누락 400).
     */
    @GetMapping("/level-labels")
    public ResponseEntity<?> levelLabels(@RequestParam(required = false) String disciplineCode) {
        if (!StringUtils.hasText(disciplineCode)) {
            throw new BadRequestException();
        }
        CollectionModel<LevelLabelResponse> model =
                CollectionModel.of(CertLevelLabels.forDiscipline(disciplineCode));
        model.add(Link.of("/docs/api.html#resource-courses-level-labels").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody CourseCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(model(courseService.create(account, request)));
    }

    /**
     * 공개 둘러보기(수강생 메인 홈) — OPEN 코스만, 종목/지역/레벨·종류/단체/가격 필터 + 정렬, 페이지네이션.
     * 빈 결과는 200(빈 페이지). "결과 N개"는 PagedModel 의 totalElements. 레벨 칩 분배 규칙은
     * {@link CourseBrowseCondition} 참고.
     *
     * <p>{@code disciplineCode} 는 <b>필수</b> — 종목별로 카탈로그가 크게 달라 화면이 항상 한 종목으로
     * 진입(메인 상단 종목 select). 누락은 400. (UI 필터엔 노출 안 하지만 호출엔 항상 채워 보낸다.)
     */
    @GetMapping("/browse")
    public ResponseEntity<?> browse(@RequestParam(required = false) String disciplineCode,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Region region,
                                    @RequestParam(required = false) List<CourseKind> kinds,
                                    @RequestParam(required = false) List<CertLevel> levels,
                                    @RequestParam(required = false) List<String> organizationCodes,
                                    @RequestParam(required = false) Integer minPrice,
                                    @RequestParam(required = false) Integer maxPrice,
                                    @RequestParam(required = false) CourseBrowseCondition.Sort sort,
                                    Pageable pageable,
                                    PagedResourcesAssembler<CourseCardResponse> assembler) {
        if (!org.springframework.util.StringUtils.hasText(disciplineCode)) {
            throw new BadRequestException(); // 종목 필수 — 종목 없는 둘러보기는 없음
        }
        CourseBrowseCondition condition = CourseBrowseCondition.builder()
                .disciplineCode(disciplineCode)
                .keyword(keyword)
                .region(region)
                .kinds(kinds)
                .levels(levels)
                .organizationCodes(organizationCodes)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .sort(sort)
                .build();
        Page<CourseCardResponse> page = courseService.browse(condition, pageable);
        PagedModel<EntityModel<CourseCardResponse>> model = assembler.toModel(page);
        model.add(Link.of("/docs/api.html#resource-courses-browse").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 내 강의 목록(카드). */
    @GetMapping("/mine")
    public ResponseEntity<?> mine(@CurrentUser Account account) {
        List<CourseResponse> courses = courseService.listMine(account);

        CollectionModel<CourseResponse> model = CollectionModel.of(courses);
        model.add(linkTo(methodOn(CourseController.class).mine(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-courses").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 내 코스 상세(편집용) — 위치별 장비 합성 포함. */
    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(courseService.get(account, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser Account account, @PathVariable Long id,
                                    @Valid @RequestBody CourseCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok().body(model(courseService.update(account, id, request)));
    }

    /** 상태 전이 — DRAFT/OPEN/CLOSED (검수 없음). */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@CurrentUser Account account, @PathVariable Long id,
                                          @Valid @RequestBody CourseStatusRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok().body(model(courseService.updateStatus(account, id, request.getStatus())));
    }

    private EntityModel<CourseResponse> model(CourseResponse response) {
        EntityModel<CourseResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-courses").withRel("profile"));
        return model;
    }
}
