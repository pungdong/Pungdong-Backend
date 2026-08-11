package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.dto.PostVisibilityRequest;
import com.diving.pungdong.community.dto.CommunityPostDetailResponse;
import com.diving.pungdong.community.dto.CommunityPostRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 커뮤니티 글 쓰기 — {@code /community/posts/**} (인증).
 *
 * <p>신원은 항상 {@code @CurrentUser} 에서 온다 — account id 를 파라미터로 받지 않는다. {@code postId} 는
 * 클라이언트가 주지만 서비스가 소유권을 검증하고, 남의 글이면 403 이 아니라 <b>400(존재 숨김)</b> 이다.
 *
 * <p>읽기는 {@link PublicCommunityController} 가 같은 경로에서 처리한다(비로그인 허용).
 */
@RestController
@RequestMapping(value = "/community/posts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CommunityPostController {

    private final CommunityPostService postService;

    /** 작성 — 브랜딩 프로필이 없으면 여기서 만든다(첫 쓰기 = 생성). */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody CommunityPostRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.create(account, request)));
    }

    /** 수정 — 미디어·태그 스냅샷 교체. */
    @PutMapping("/{postId}")
    public ResponseEntity<?> update(@CurrentUser Account account,
                                    @PathVariable Long postId,
                                    @Valid @RequestBody CommunityPostRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.update(account, postId, request)));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long postId) {
        postService.delete(account, postId);
        return ResponseEntity.noContent().build();
    }

    /** 숨기기 — 삭제와 다르다(되돌릴 수 있고 공개 경로에서만 빠진다). */
    @PatchMapping("/{postId}/visibility")
    public ResponseEntity<?> visibility(@CurrentUser Account account,
                                        @PathVariable Long postId,
                                        @Valid @RequestBody PostVisibilityRequest request,
                                        BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.updateHidden(account, postId, request.getHidden())));
    }

    private void reject(BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
    }

    private EntityModel<CommunityPostDetailResponse> model(CommunityPostDetailResponse response) {
        EntityModel<CommunityPostDetailResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-community-post").withRel("profile"));
        return model;
    }
}
