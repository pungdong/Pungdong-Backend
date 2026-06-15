package com.diving.pungdong.enrollment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.enrollment.dto.InstructorEnrollmentResponse;
import com.diving.pungdong.enrollment.dto.RejectRequest;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    private EntityModel<InstructorEnrollmentResponse> model(InstructorEnrollmentResponse response) {
        EntityModel<InstructorEnrollmentResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-instructor-enrollments").withRel("profile"));
        return model;
    }
}
