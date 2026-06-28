package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.dto.EnrollmentOptionsResponse;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.enrollment.dto.InstructorScheduleHubResponse;
import com.diving.pungdong.enrollment.dto.ProposeSlotsRequest;
import com.diving.pungdong.enrollment.dto.RejectRequest;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 수강신청 — 강사 측(받은 신청 검토 · 수락 · 거절). V2 enrollment-management 검토 시트의 BE.
 *
 * <p>매처: {@code /instructor/enrollments/**} → authenticated (서비스 게이트=강사신청 보유, venue/availability 기조).
 */
@RestController
@RequestMapping(value = "/instructor/enrollments", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class InstructorEnrollmentController {

    private final InstructorEnrollmentService instructorEnrollmentService;
    private final EnrollmentOptionsService optionsService;

    /**
     * 강사 수강관리 hub — 거래 단위(수강생×강의) 카드 + 필터 카운트. 신청검토·일정변경검토·마무리를 한 곳에서.
     * {@code filter} = all(기본)|action|progress|completed. 학생 hub(GET /enrollments/mine/schedule)의 강사 거울.
     */
    @GetMapping("/hub")
    public ResponseEntity<?> hub(@CurrentUser Account account,
                                 @RequestParam(required = false) String filter) {
        EntityModel<InstructorScheduleHubResponse> model =
                EntityModel.of(instructorEnrollmentService.hub(account, filter));
        model.add(Link.of("/docs/api.html#resource-instructor-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 받은 신청 목록 — 상태별(기본 PENDING). */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Account account,
                                  @RequestParam(required = false) EnrollmentStatus status) {
        List<InstructorEnrollmentResponse> list = instructorEnrollmentService.list(account, status);
        CollectionModel<InstructorEnrollmentResponse> model = CollectionModel.of(list);
        model.add(Link.of("/docs/api.html#resource-instructor-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(instructorEnrollmentService.accept(account, id)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@CurrentUser Account account, @PathVariable Long id,
                                    @RequestBody(required = false) RejectRequest request) {
        String reason = request == null ? null : request.getReason();
        return ResponseEntity.ok().body(model(instructorEnrollmentService.reject(account, id, reason)));
    }

    /**
     * 일정변경 제안 옵션 — 강사가 그 회차에 제안할 대안 슬롯 후보(교집합, {@code remaining/full} 포함). 학생
     * round options 와 대칭이라 FE 가 같은 컴포넌트로 캘린더를 채우고 만석 슬롯을 비활성화한다. 위치는 회차 고정.
     */
    @GetMapping("/{id}/propose-options")
    public ResponseEntity<?> proposeOptions(@CurrentUser Account account, @PathVariable Long id) {
        EnrollmentOptionsResponse options = optionsService.getInstructorRoundOptions(account, id, LocalDate.now());
        EntityModel<EnrollmentOptionsResponse> model = EntityModel.of(options);
        model.add(Link.of("/docs/api.html#resource-instructor-enrollments").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 일정변경요청 — 위치 고정, 완전한 대안 슬롯(날짜+이용권+블록) 제안. 학생이 고르면 사전 수락(곧장 결제 대기). */
    @PostMapping("/{id}/propose-slots")
    public ResponseEntity<?> proposeSlots(@CurrentUser Account account, @PathVariable Long id,
                                          @RequestBody ProposeSlotsRequest request) {
        return ResponseEntity.ok().body(model(instructorEnrollmentService.proposeSlots(account, id,
                request == null ? null : request.getSlots())));
    }

    /** 회차 완료 — 강사가 그 회차 수강을 마쳤다고 표시(done). 확정 회차만. 다음 회차 게이트가 열린다. */
    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(instructorEnrollmentService.completeRound(account, id)));
    }

    /** 일정 통째 완료 — 그 세션의 모든 확정 회차를 일괄 done(빠른 정산). */
    @PostMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<?> completeSession(@CurrentUser Account account, @PathVariable Long sessionId) {
        int completed = instructorEnrollmentService.completeSession(account, sessionId);
        return ResponseEntity.ok().body(java.util.Map.of("completed", completed));
    }

    private EntityModel<InstructorEnrollmentResponse> model(InstructorEnrollmentResponse response) {
        EntityModel<InstructorEnrollmentResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-instructor-enrollments").withRel("profile"));
        return model;
    }
}
