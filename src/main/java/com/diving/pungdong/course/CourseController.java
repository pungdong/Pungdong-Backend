package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.course.dto.CourseCreateRequest;
import com.diving.pungdong.course.dto.CourseResponse;
import com.diving.pungdong.course.dto.CourseStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
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
 * 공개(수강생) 조회 엔드포인트는 후속. PII 없음 → GET 무방.
 */
@RestController
@RequestMapping(value = "/courses", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody CourseCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(model(courseService.create(account, request)));
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
