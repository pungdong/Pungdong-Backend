package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.storage.BrandingImageStorage;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.global.validation.ImageUploadPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 브랜딩 사진 업로드 (2-phase 1단계) — multipart 로 먼저 올려 URL 을 받고, 게시물 저장 JSON 이 그 URL 을
 * 참조한다({@code /course-images} 와 동일 패턴).
 *
 * <p>검증은 {@link ImageUploadPolicy} 공통 정책을 쓴다 — 형식 allowlist·8MB·빈 파일 거부.
 * 8MB 는 임의 숫자가 아니라 {@code /r/} 변환 Lambda 가 원본을 메모리에 올린 뒤 base64 로 반환해
 * Function URL 페이로드 상한(~6MB)에 걸리는 데서 역산한 값이다.
 */
@RestController
@RequestMapping(value = "/branding-images", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class BrandingImageController {

    private final BrandingImageStorage storage;

    @PostMapping
    public ResponseEntity<?> upload(@CurrentUser Account account,
                                    @RequestParam("image") MultipartFile image) {
        ImageUploadPolicy.validate(image);
        try {
            EntityModel<Map<String, String>> model =
                    EntityModel.of(Map.of("fileURL", storage.store(image)));
            model.add(Link.of("/docs/api.html#resource-branding-image").withRel("profile"));
            return ResponseEntity.ok().body(model);
        } catch (IOException e) {
            throw new BadRequestException();
        }
    }
}
