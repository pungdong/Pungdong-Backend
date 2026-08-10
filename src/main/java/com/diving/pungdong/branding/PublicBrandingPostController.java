package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.dto.BrandingPostDetailResponse;
import com.diving.pungdong.global.security.CurrentUser;
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
 * <p>미발행 프로필의 글이거나 숨긴 글이면 <b>400(존재 숨김)</b>. 단 <b>오너 본인은 자기 글이면 숨김·미발행
 * 이어도 볼 수 있다</b> — 상세에서 바로 "다시 공개"를 누르는 경로가 그 예외를 전제한다.
 *
 * <p>permitAll 이라 인증이 없을 수 있어 {@code @CurrentUser} 는 <b>null 이 올 수 있다</b>.
 */
@RestController
@RequestMapping(value = "/branding-posts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicBrandingPostController {

    private final BrandingPostService postService;

    @GetMapping("/{postId}")
    public ResponseEntity<?> detail(@CurrentUser Account viewer, @PathVariable Long postId) {
        EntityModel<BrandingPostDetailResponse> model = EntityModel.of(postService.detail(postId, viewer));
        model.add(Link.of("/docs/api.html#resource-branding-post-public").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
