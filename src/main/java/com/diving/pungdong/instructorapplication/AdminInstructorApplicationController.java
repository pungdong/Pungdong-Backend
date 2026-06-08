package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationDetail;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationSummary;
import com.diving.pungdong.instructorapplication.dto.RejectRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 강사 신청 심사 (어드민 전용). 권한 매처: {@code /admin/instructor-applications/**} hasRole(ADMIN).
 */
@RestController
@RequestMapping(value = "/admin/instructor-applications", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminInstructorApplicationController {

    private final InstructorApplicationService applicationService;

    /** 상태별 신청 목록 (기본 SUBMITTED = 심사 대기). 승인/반려된 건은 빠진다. */
    @GetMapping
    public ResponseEntity<?> getApplications(
            @RequestParam(name = "status", defaultValue = "SUBMITTED") InstructorApplicationStatus status,
            Pageable pageable,
            PagedResourcesAssembler<InstructorApplicationSummary> assembler) {
        Page<InstructorApplicationSummary> page = applicationService.getApplications(status, pageable);
        PagedModel<EntityModel<InstructorApplicationSummary>> model = assembler.toModel(page);
        return ResponseEntity.ok().body(model);
    }

    /** 신청 상세 (본인확인 PII 포함). */
    @GetMapping("/{applicationId}")
    public ResponseEntity<?> getApplicationDetail(@PathVariable Long applicationId) {
        InstructorApplicationDetail detail = applicationService.getApplicationDetail(applicationId);

        EntityModel<InstructorApplicationDetail> model = EntityModel.of(detail);
        model.add(linkTo(methodOn(AdminInstructorApplicationController.class).getApplicationDetail(applicationId)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-admin-instructor-application-detail").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 승인 — 신청자에게 INSTRUCTOR 권한 부여 (STUDENT 유지). */
    @PostMapping("/{applicationId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long applicationId,
                                     @CurrentUser Account reviewer) {
        applicationService.approve(applicationId, reviewer);

        EntityModel<SuccessResult> model = EntityModel.of(SuccessResult.builder().success(true).build());
        model.add(linkTo(methodOn(AdminInstructorApplicationController.class).getApplicationDetail(applicationId)).withRel("application"));
        model.add(Link.of("/docs/api.html#resource-admin-instructor-application-approve").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 반려 — 사유 저장, 신청자는 수정 후 재제출 가능. */
    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<?> reject(@PathVariable Long applicationId,
                                    @CurrentUser Account reviewer,
                                    @Valid @RequestBody RejectRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        applicationService.reject(applicationId, reviewer, request.getReason());

        EntityModel<SuccessResult> model = EntityModel.of(SuccessResult.builder().success(true).build());
        model.add(linkTo(methodOn(AdminInstructorApplicationController.class).getApplicationDetail(applicationId)).withRel("application"));
        model.add(Link.of("/docs/api.html#resource-admin-instructor-application-reject").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
