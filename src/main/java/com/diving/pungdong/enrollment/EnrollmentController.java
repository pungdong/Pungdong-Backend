package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.enrollment.dto.EnrollmentOptionsResponse;
import com.diving.pungdong.enrollment.dto.EnrollmentResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 수강신청 — 학생 측. 강의 상세 "신청하기" → 옵션 조회 → 신청 → 강사 답변 대기.
 *
 * <p>매처: {@code /enrollments/**} → authenticated. 결제는 v1 에 없음(강사 확정 후 PG, 후속).
 * 상세 docs/features/booking.md.
 */
@RestController
@RequestMapping(value = "/enrollments", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentOptionsService optionsService;
    private final EnrollmentService enrollmentService;

    /** 신청 옵션 — 교집합(강사 availability ∩ venue 운영블록 ∩ 코스 1회차 위치) 평탄 슬롯 + 장비. */
    @GetMapping("/options")
    public ResponseEntity<?> options(@CurrentUser Account account, @RequestParam Long courseId) {
        EnrollmentOptionsResponse options = optionsService.getOptions(account, courseId, LocalDate.now());
        EntityModel<EnrollmentOptionsResponse> model = EntityModel.of(options);
        model.add(Link.of("/docs/api.html#resource-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 신청 — PENDING 생성(강사 답변 대기). 서버가 정원·exact-match·장비·가격 재검증. */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody EnrollmentCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(model(enrollmentService.submit(account, request)));
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine(@CurrentUser Account account) {
        List<EnrollmentResponse> list = enrollmentService.listMine(account);
        CollectionModel<EnrollmentResponse> model = CollectionModel.of(list);
        model.add(Link.of("/docs/api.html#resource-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(enrollmentService.cancel(account, id)));
    }

    private EntityModel<EnrollmentResponse> model(EnrollmentResponse response) {
        EntityModel<EnrollmentResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-enrollments").withRel("profile"));
        return model;
    }
}
