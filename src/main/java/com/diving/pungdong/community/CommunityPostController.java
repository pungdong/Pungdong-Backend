package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.community.dto.CommunityPostCardResponse;
import com.diving.pungdong.community.dto.CommunityPostDetailResponse;
import com.diving.pungdong.community.dto.PostVisibilityRequest;
import com.diving.pungdong.community.dto.CommunityPostRequest;
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
    private final CommunityReactionService reactionService;

    /**
     * 내가 쓴 글 — <b>숨김·프로필 미노출 포함</b>한 내 글 전부. 배열은 {@code _embedded.posts}.
     *
     * <p>리터럴 {@code /me} 는 공개 상세({@code GET /community/posts/{postId}})보다 <b>구체적</b>이라
     * 라우팅이 이쪽으로 온다. 다만 시큐리티 매처는 패턴 순서대로라
     * {@code GET /community/posts/*} 의 permitAll 앞에 {@code /community/posts/me} 를 따로 둬야
     * 비로그인 요청이 여기까지 와서 {@code @CurrentUser} 가 null 이 되는 일이 없다.
     */
    @GetMapping("/me")
    public ResponseEntity<?> myPosts(@CurrentUser Account account,
                                     Pageable pageable,
                                     PagedResourcesAssembler<CommunityPostCardResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(postService.myPosts(account, pageable)));
    }

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

    /**
     * 숨기기 — 삭제와 다르다(되돌릴 수 있고 공개 경로에서만 빠진다).
     *
     * <p><b>모든 글의 유일한 숨김 경로다.</b> 숨김은 전역 스위치(컬럼 하나)라 여기서 켜면 커뮤니티 피드와
     * 브랜딩 그리드 양쪽에서 빠지고, 여기서 풀면 양쪽이 함께 돌아온다. 관문이 {@code showOnProfile} 을
     * 보지 않으므로 <b>프로필에 올린 글이든 커뮤니티 전용 글이든</b> 이 경로 하나로 토글한다.
     */
    @PatchMapping("/{postId}/visibility")
    public ResponseEntity<?> visibility(@CurrentUser Account account,
                                        @PathVariable Long postId,
                                        @Valid @RequestBody PostVisibilityRequest request,
                                        BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(model(postService.updateHidden(account, postId, request.getHidden())));
    }

    /* ─── 좋아요·북마크 ───────────────────────────────────── */
    // 전부 멱등이다 — 같은 요청을 두 번 보내도 결과가 같다((대상, 계정) UNIQUE).
    // 응답에 갱신된 카운트와 내 상태를 함께 실어, 낙관적 업데이트가 항상 수렴하게 한다.

    @PostMapping("/{postId}/like")
    public ResponseEntity<?> like(@CurrentUser Account account, @PathVariable Long postId) {
        return ResponseEntity.ok().body(reactionService.like(account, postId));
    }

    @DeleteMapping("/{postId}/like")
    public ResponseEntity<?> unlike(@CurrentUser Account account, @PathVariable Long postId) {
        return ResponseEntity.ok().body(reactionService.unlike(account, postId));
    }

    @PostMapping("/{postId}/bookmark")
    public ResponseEntity<?> bookmark(@CurrentUser Account account, @PathVariable Long postId) {
        return ResponseEntity.ok().body(reactionService.bookmark(account, postId));
    }

    @DeleteMapping("/{postId}/bookmark")
    public ResponseEntity<?> unbookmark(@CurrentUser Account account, @PathVariable Long postId) {
        return ResponseEntity.ok().body(reactionService.unbookmark(account, postId));
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
