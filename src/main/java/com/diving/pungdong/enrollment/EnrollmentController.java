package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.enrollment.dto.EnrollmentOptionsResponse;
import com.diving.pungdong.enrollment.dto.EnrollmentResponse;
import com.diving.pungdong.enrollment.dto.PickDateRequest;
import com.diving.pungdong.enrollment.dto.RoundScheduleRequest;
import com.diving.pungdong.enrollment.dto.ScheduleHubResponse;
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

    /** 다음 회차 옵션 — 이 수강에서 지금 잡을 수 있는 회차의 교집합 슬롯+장비. 없으면 슬롯 비어 옴. */
    @GetMapping("/{enrollmentId}/next-options")
    public ResponseEntity<?> nextOptions(@CurrentUser Account account, @PathVariable Long enrollmentId) {
        EnrollmentOptionsResponse options = optionsService.getNextOptions(account, enrollmentId, LocalDate.now());
        EntityModel<EnrollmentOptionsResponse> model = EntityModel.of(options);
        model.add(Link.of("/docs/api.html#resource-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 2회차+ 일정 신청 — 다음 schedulable 회차를 PENDING 으로 추가(순차 게이트·EXTRA). */
    @PostMapping("/{enrollmentId}/rounds")
    public ResponseEntity<?> scheduleRound(@CurrentUser Account account, @PathVariable Long enrollmentId,
                                           @Valid @RequestBody RoundScheduleRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(model(enrollmentService.scheduleNextRound(account, enrollmentId, request)));
    }

    /** 강사 일정변경요청 중 제안 날짜 선택 — 사전 수락이라 곧장 결제 대기(PAYMENT_PENDING). */
    @PostMapping("/rounds/{roundId}/pick-date")
    public ResponseEntity<?> pickDate(@CurrentUser Account account, @PathVariable Long roundId,
                                      @Valid @RequestBody PickDateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok().body(model(enrollmentService.pickDate(account, roundId, request.getDate())));
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine(@CurrentUser Account account) {
        List<EnrollmentResponse> list = enrollmentService.listMine(account);
        CollectionModel<EnrollmentResponse> model = CollectionModel.of(list);
        model.add(Link.of("/docs/api.html#resource-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 수강생 강의일정 hub — 내 신청을 강의 단위로 그룹핑 + 진행상태 파생 + 필터 카운트. */
    @GetMapping("/mine/schedule")
    public ResponseEntity<?> mySchedule(@CurrentUser Account account) {
        EntityModel<ScheduleHubResponse> model = EntityModel.of(enrollmentService.mySchedule(account));
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
