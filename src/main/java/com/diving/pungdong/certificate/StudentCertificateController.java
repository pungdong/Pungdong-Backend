package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.CertificatePhotoResult;
import com.diving.pungdong.certificate.dto.StudentCertificateCreateRequest;
import com.diving.pungdong.certificate.dto.StudentCertificateResponse;
import com.diving.pungdong.certificate.dto.StudentCertificateUpdateRequest;
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
 * <p><b>수정은 {@code PUT}(전면 교체)이고 {@code PATCH} 는 없다</b> — 편집 폼이 카드 전체를 다시 보내므로
 * 부분 갱신 계약이 필요 없다. 단 <b>사진만 "생략 = 유지"</b> 로 예외다(2-phase 업로드라 번호 오타 하나
 * 고치려고 카드를 다시 찍게 만들 수 없다). 이유는 {@code StudentCertificateService.update} 참조.
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
        rejectIfInvalid(result);
        StudentCertificateResponse created = certificateService.register(account, request);

        EntityModel<StudentCertificateResponse> model = EntityModel.of(created);
        model.add(linkTo(methodOn(StudentCertificateController.class).getMine(account)).withRel("mine"));
        model.add(Link.of("/docs/api.html#resource-certificates-register").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /**
     * 수정 — 본인 소유만, <b>전면 교체</b>. 없거나 남의 것이면 404(존재 숨김, 등록 외 다른 경로와 동일).
     *
     * <p>사진은 {@code photoFileKey} 를 <b>비워 보내면 기존 것을 유지</b>하고, 새 key 를 보내면 교체하며
     * 옛 객체를 파기한다. {@code enrollmentId} 를 빼면 강의 연결이 해제된다({@code source=EXTERNAL}).
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser Account account, @PathVariable Long id,
                                    @Valid @RequestBody StudentCertificateUpdateRequest request,
                                    BindingResult result) {
        rejectIfInvalid(result);
        StudentCertificateResponse updated = certificateService.update(account, id, request);

        EntityModel<StudentCertificateResponse> model = EntityModel.of(updated);
        model.add(linkTo(methodOn(StudentCertificateController.class).getOne(account, id)).withSelfRel());
        model.add(linkTo(methodOn(StudentCertificateController.class).getMine(account)).withRel("mine"));
        model.add(Link.of("/docs/api.html#resource-certificates-update").withRel("profile"));
        return ResponseEntity.ok().body(model);
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

    /**
     * 형식 오류를 400 + <b>어느 필드가 왜 틀렸는지</b>로 바꾼다 — 형식 규칙은 이미 공개 정보라(FE·types.ts)
     * 숨겨서 얻는 게 없고, 사용자는 무엇을 고칠지 알아야 한다(레포 규약).
     *
     * <p>클래스 레벨 제약이 생기면 {@code getFieldError()} 가 null 이라 NPE→500 이 된다(지금은 필드
     * 제약뿐이지만 방어). 등록·수정이 같은 문구를 내도록 한 곳에 모았다.
     */
    private void rejectIfInvalid(BindingResult result) {
        if (!result.hasErrors()) {
            return;
        }
        throw new BadRequestException(java.util.Optional.ofNullable(result.getFieldError())
                .map(org.springframework.validation.FieldError::getDefaultMessage)
                .orElse("입력값을 확인해주세요."));
    }
}
