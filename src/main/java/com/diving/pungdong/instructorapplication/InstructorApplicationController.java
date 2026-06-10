package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.instructorapplication.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 강사 신청 (신청자 본인용). 흐름: 본인확인 → 자격증 이미지 업로드 → 제출 → (반려 시) 재제출.
 *
 * <p>로그인한 사용자면 누구나(STUDENT) 접근 — 권한 매처는 {@code /instructor-applications/**}
 * authenticated. 어드민 심사 엔드포인트는 {@link AdminInstructorApplicationController}.
 */
@RestController
@RequestMapping(value = "/instructor-applications", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class InstructorApplicationController {

    private final InstructorApplicationService applicationService;

    /**
     * 내 신청 목록 — 종목별 여러 건. 프로필 탭 버튼/타임라인용. 미신청 종목은 리스트에 없음
     * (FE 가 선택된 종목으로 필터 → 없으면 "신청하기" 노출).
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyApplications(@CurrentUser Account account) {
        List<MyInstructorApplicationResponse> responses = applicationService.getMyApplications(account);

        CollectionModel<MyInstructorApplicationResponse> model = CollectionModel.of(responses);
        model.add(linkTo(methodOn(InstructorApplicationController.class).getMyApplications(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-instructor-application-me").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 자격증 이미지 업로드 (2-phase 1단계) — multipart, S3 URL 반환.
     *
     * <p>본인확인은 이 도메인이 아니라 공유 자산: {@code POST /identity-verifications} 로 생성하고
     * {@code GET /identity-verifications/me} 로 조회 (수강/강사 공유, skip 지원).
     */
    @PostMapping("/certificate-images")
    public ResponseEntity<?> uploadCertificateImage(@CurrentUser Account account,
                                                    @RequestParam("image") MultipartFile image) {
        CertificateImageResult uploaded = applicationService.uploadCertificateImage(account, image);

        EntityModel<CertificateImageResult> model = EntityModel.of(uploaded);
        model.add(Link.of("/docs/api.html#resource-instructor-application-certificate-image").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 신청 제출 (2-phase 2단계). */
    @PostMapping
    public ResponseEntity<?> submit(@CurrentUser Account account,
                                    @Valid @RequestBody InstructorApplicationSubmitRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        InstructorApplicationResult applicationResult = applicationService.submit(account, request);

        EntityModel<InstructorApplicationResult> model = EntityModel.of(applicationResult);
        model.add(linkTo(methodOn(InstructorApplicationController.class).getMyApplications(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-instructor-application-submit").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /** 내 신청 수정·재제출 (반려 후 등). */
    @PutMapping("/me")
    public ResponseEntity<?> resubmit(@CurrentUser Account account,
                                      @Valid @RequestBody InstructorApplicationSubmitRequest request,
                                      BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        InstructorApplicationResult applicationResult = applicationService.resubmit(account, request);

        EntityModel<InstructorApplicationResult> model = EntityModel.of(applicationResult);
        model.add(linkTo(methodOn(InstructorApplicationController.class).getMyApplications(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-instructor-application-resubmit").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 자격증 추가 (자격증 관리 탭) — 이미 승인된 강사가 그 종목에 자격증 1건 append. MVP 는 검수 없이
     * 즉시 반영. (검수 중/반려는 제출·재제출 경로.)
     */
    @PostMapping("/certificates")
    public ResponseEntity<?> addCertificate(@CurrentUser Account account,
                                            @Valid @RequestBody AddCertificateRequest request,
                                            BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        InstructorApplicationResult applicationResult = applicationService.addCertificate(account, request);

        EntityModel<InstructorApplicationResult> model = EntityModel.of(applicationResult);
        model.add(linkTo(methodOn(InstructorApplicationController.class).getMyApplications(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-instructor-application-add-certificate").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
