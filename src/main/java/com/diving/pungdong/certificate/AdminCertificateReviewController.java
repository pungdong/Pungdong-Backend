package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.CertificateReviewCounts;
import com.diving.pungdong.certificate.dto.CertificateReviewDetail;
import com.diving.pungdong.certificate.dto.CertificateReviewRejectRequest;
import com.diving.pungdong.certificate.dto.CertificateReviewSummary;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.global.persistence.PageClamp;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Optional;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 어드민 검수 큐 — 강사 신청(NEW)·추가 자격증(ADDITIONAL)·재검수(RE_VERIFY)를 <b>한 목록</b>으로.
 * 권한 매처: {@code /admin/certificate-reviews/**} hasRole(ADMIN). 기존 {@code /admin/instructor-applications/**} 는
 * 신청 단위 보조 경로로 남는다(NEW 만 보인다).
 */
@RestController
@RequestMapping(value = "/admin/certificate-reviews", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminCertificateReviewController {

    private final CertificateReviewService reviewService;

    /**
     * {@code status} 생략 시 전체(이력 포함), 지정 시 그 상태만. 정렬은 서버 고정(요청 최신순 + id tie-break —
     * 서비스가 붙인다; {@code PageClamp} 가 클라이언트 {@code ?sort=} 를 버린다). size 상한 50.
     */
    @GetMapping
    public ResponseEntity<?> getReviews(
            @RequestParam(name = "status", required = false) CertificateReviewStatus status,
            @PageableDefault(size = 20) Pageable pageable,
            PagedResourcesAssembler<CertificateReviewSummary> assembler) {
        Page<CertificateReviewSummary> page = reviewService.getReviews(status, PageClamp.fixed(pageable));
        PagedModel<EntityModel<CertificateReviewSummary>> model = assembler.toModel(page);
        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/counts")
    public ResponseEntity<?> getCounts() {
        EntityModel<CertificateReviewCounts> model = EntityModel.of(reviewService.getCounts());
        model.add(linkTo(methodOn(AdminCertificateReviewController.class).getCounts()).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-admin-certificate-review-counts").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/{reviewId}")
    public ResponseEntity<?> getDetail(@PathVariable Long reviewId) {
        EntityModel<CertificateReviewDetail> model = EntityModel.of(reviewService.getDetail(reviewId));
        model.add(linkTo(methodOn(AdminCertificateReviewController.class).getDetail(reviewId)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-admin-certificate-review-detail").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** NEW 는 강사 신청 승인(INSTRUCTOR 부여 + 첨부 VERIFIED), 나머지는 그 자격증 VERIFIED. */
    @PostMapping("/{reviewId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long reviewId, @CurrentUser Account reviewer) {
        reviewService.approve(reviewId, reviewer);
        return ResponseEntity.ok().body(result(reviewId, "approve"));
    }

    @PostMapping("/{reviewId}/reject")
    public ResponseEntity<?> reject(@PathVariable Long reviewId, @CurrentUser Account reviewer,
                                    @Valid @RequestBody CertificateReviewRejectRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(Optional.ofNullable(result.getFieldError())
                    .map(FieldError::getDefaultMessage).orElse("입력값을 확인해주세요."));
        }
        reviewService.reject(reviewId, reviewer, request.getReason());
        return ResponseEntity.ok().body(result(reviewId, "reject"));
    }

    private EntityModel<SuccessResult> result(Long reviewId, String action) {
        EntityModel<SuccessResult> model = EntityModel.of(SuccessResult.builder().success(true).build());
        model.add(linkTo(methodOn(AdminCertificateReviewController.class).getDetail(reviewId)).withRel("review"));
        model.add(Link.of("/docs/api.html#resource-admin-certificate-review-" + action).withRel("profile"));
        return model;
    }
}
