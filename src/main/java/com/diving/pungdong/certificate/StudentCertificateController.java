package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.CertificatePhotoResult;
import com.diving.pungdong.certificate.dto.StudentCertificateCreateRequest;
import com.diving.pungdong.certificate.dto.StudentCertificateResponse;
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
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 학생 보유 자격증 (프로필 탭 &gt; 내 자격증). 전부 <b>본인 것만</b> — 매처는
 * {@code SecurityConfiguration} 의 {@code /certificates/**} authenticated.
 *
 * <p>⚠️ 기존 {@code POST /instructor-applications/certificates}(강사 자격증 append)와 <b>다른 리소스</b>다.
 *
 * <p><b>수정(PUT/PATCH)이 없는 것은 의도다</b> — FE 에 편집 화면이 없다(스파인 결정). 만들면 도달 불가
 * API 가 된다. 편집 화면이 생기는 PR 에서 추가한다(엔티티는 막지 않는다).
 */
@RestController
@RequestMapping(value = "/certificates", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class StudentCertificateController {

    private final StudentCertificateService certificateService;

    /** 내 자격증 목록. 빈 목록은 200 + {@code _embedded} 부재(FE 는 `?? []`). */
    @GetMapping("/mine")
    public ResponseEntity<?> getMine(@CurrentUser Account account) {
        List<StudentCertificateResponse> certificates = certificateService.getMine(account);

        CollectionModel<StudentCertificateResponse> model = CollectionModel.of(certificates);
        model.add(linkTo(methodOn(StudentCertificateController.class).getMine(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-certificates-mine").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 단건 — 상세 진입 시 사진 presigned 를 <b>새로</b> 받기 위한 경로(TTL 3분이라 목록 값이 만료될 수 있다).
     * 없거나 남의 것이면 404(존재 숨김).
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@CurrentUser Account account, @PathVariable Long id) {
        EntityModel<StudentCertificateResponse> model = EntityModel.of(certificateService.getOne(account, id));
        model.add(Link.of("/docs/api.html#resource-certificates-one").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 등록. 사진은 {@code POST /certificates/photos} 로 먼저 올리고 {@code photoFileKey} 로 참조한다. */
    @PostMapping
    public ResponseEntity<?> register(@CurrentUser Account account,
                                      @Valid @RequestBody StudentCertificateCreateRequest request,
                                      BindingResult result) {
        if (result.hasErrors()) {
            // 어느 필드가 왜 틀렸는지 그대로 노출 — 형식 규칙은 공개 정보라 숨길 이득이 없다(레포 규약).
            // 클래스 레벨 제약이 생기면 getFieldError() 가 null 이라 NPE→500 이 된다(지금은 필드 제약뿐이지만 방어).
            throw new BadRequestException(java.util.Optional.ofNullable(result.getFieldError())
                    .map(org.springframework.validation.FieldError::getDefaultMessage)
                    .orElse("입력값을 확인해주세요."));
        }
        StudentCertificateResponse created = certificateService.register(account, request);

        EntityModel<StudentCertificateResponse> model = EntityModel.of(created);
        model.add(linkTo(methodOn(StudentCertificateController.class).getMine(account)).withRel("mine"));
        model.add(Link.of("/docs/api.html#resource-certificates-register").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /** 삭제 — 본인 소유만. DB 행 + 사진 객체까지 제거한다. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long id) {
        certificateService.delete(account, id);
        return ResponseEntity.noContent().build();
    }

    /** 사진 업로드(2-phase 1단계) — multipart, 파트 이름 {@code image}. 응답의 key 를 등록 JSON 에 넣는다. */
    @PostMapping("/photos")
    public ResponseEntity<?> uploadPhoto(@CurrentUser Account account,
                                         @RequestParam("image") MultipartFile image) {
        CertificatePhotoResult uploaded = certificateService.uploadPhoto(account, image);

        EntityModel<CertificatePhotoResult> model = EntityModel.of(uploaded);
        model.add(Link.of("/docs/api.html#resource-certificate-photo").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
