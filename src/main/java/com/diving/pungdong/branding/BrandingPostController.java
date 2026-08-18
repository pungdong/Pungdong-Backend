package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.dto.*;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 게시물 오너 CRUD — {@code /branding/me/posts/**} (인증).
 *
 * <p>신원은 항상 {@code @CurrentUser} 에서 온다. {@code {postId}} 는 클라이언트가 주지만 서비스가 소유권을
 * 검증하고, 남의 글이면 403 이 아니라 <b>400(존재 숨김)</b> 으로 답한다.
 *
 * <p><b>숨김 토글은 여기 없다 — {@code PATCH /community/posts/{id}/visibility} 하나다.</b> 숨김은
 * 컬럼 하나짜리 전역 스위치라 경로가 둘이면 규칙이 갈린다. 실제로 갈렸다: 이 경로는 프로필 글에만
 * 동작해 커뮤니티 전용 글을 못 숨겼고, 어드민 조치(ACTIONED) 확인이 없어 신고로 내려간 글을 작성자가
 * 되살릴 수 있었다. 작성·수정도 마찬가지로 통합 폼({@code /community/posts})이 주 경로이고,
 * 여기 남은 것은 <b>구버전 앱 호환</b>과 프로필 전용 동작(고정)이다.
 */
@RestController
@RequestMapping(value = "/branding/me/posts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class BrandingPostController {

    private final BrandingPostService postService;

    /** 오너 목록 — 숨긴 글 포함. 프로필이 아직 없으면 빈 페이지(400 아님). */
    @GetMapping
    public ResponseEntity<?> myPosts(@CurrentUser Account account,
                                     Pageable pageable,
                                     PagedResourcesAssembler<BrandingPostCardResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(postService.myGrid(account, pageable)));
    }

    /** 작성 — 프로필이 없으면 여기서 만든다(첫 쓰기 = 생성). */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody BrandingPostRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.create(account, request)));
    }

    /** 수정 — 미디어·태그 스냅샷 교체. */
    @PutMapping("/{postId}")
    public ResponseEntity<?> update(@CurrentUser Account account,
                                    @PathVariable Long postId,
                                    @Valid @RequestBody BrandingPostRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.update(account, postId, request)));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long postId) {
        postService.delete(account, postId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{postId}/pin")
    public ResponseEntity<?> pin(@CurrentUser Account account,
                                 @PathVariable Long postId,
                                 @Valid @RequestBody PostPinRequest request,
                                 BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.updatePinned(account, postId, request.getPinned())));
    }

    private void reject(BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
    }

    private EntityModel<BrandingPostDetailResponse> model(BrandingPostDetailResponse response) {
        EntityModel<BrandingPostDetailResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-branding-post").withRel("profile"));
        return model;
    }
}
