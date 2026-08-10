package com.diving.pungdong.branding;

import com.diving.pungdong.branding.dto.BrandingPostDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게시물 상세 — {@code GET /branding-posts/{postId}} (<b>비로그인 가능</b>).
 *
 * <p>미발행 프로필의 글이거나 숨긴 글이면 <b>400(존재 숨김)</b>. 오너가 자기 숨긴 글을 보는 경로는
 * 오너 목록·수정 API 다(이 공개 경로는 항상 공개분만 준다).
 */
@RestController
@RequestMapping(value = "/branding-posts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicBrandingPostController {

    private final BrandingPostService postService;

    @GetMapping("/{postId}")
    public ResponseEntity<?> detail(@PathVariable Long postId) {
        EntityModel<BrandingPostDetailResponse> model = EntityModel.of(postService.publicDetail(postId));
        model.add(Link.of("/docs/api.html#resource-branding-post-public").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
