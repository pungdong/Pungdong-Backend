package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.community.dto.CommunityCommentRequest;
import com.diving.pungdong.community.dto.MyPostCommentResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 댓글 쓰기 — 인증. 읽기(스레드 조회)는 {@link PublicCommunityController} 가 비로그인으로 연다.
 *
 * <p>작성은 게시물 하위 경로({@code /community/posts/{postId}/comments}), 수정·삭제·좋아요는 댓글
 * 자체 경로({@code /community/comments/{commentId}})다 — 댓글 id 만으로 대상이 정해지므로 게시물 id 를
 * 다시 받을 이유가 없고, 받으면 두 값이 어긋나는 경우를 검증해야 한다.
 *
 * <p>"내 글에 달린 댓글" 만 게시물 하위의 읽기 경로({@code /community/posts/me/comments})다 —
 * "내가 쓴 글"({@code /community/posts/me})과 같은 축이라 소유가 경로에 드러난다.
 * {@code /community/comments/me} 로 두지 않은 이유는 그 이름이 "내가 <b>쓴</b> 댓글" 로 읽히는데
 * 여기서 주는 건 정반대이기 때문이다.
 */
@RestController
@RequiredArgsConstructor
public class CommunityCommentController {

    private final CommunityCommentService commentService;

    /**
     * 내 글에 달린 최근 댓글(최신순 고정, 인증). 리터럴 {@code /me} 가 {@code {postId}} 보다
     * <b>구체적</b>이라 라우팅은 이쪽으로 온다.
     *
     * <p>⚠️ 다만 <b>시큐리티 매처는 패턴 순서대로</b>다. 스레드 조회의 permitAll 매처가 와일드카드
     * 자리에 {@code me} 를 넣어 이 경로까지 잡으므로, 리터럴 매처를 그 앞에 두지 않으면 비로그인
     * 요청이 컨트롤러까지 와서 {@code @CurrentUser} 가 null 이 된다(401 이 아니라 500).
     */
    @GetMapping(value = "/community/posts/me/comments", produces = MediaTypes.HAL_JSON_VALUE)
    public ResponseEntity<?> onMyPosts(@CurrentUser Account account,
                                       Pageable pageable,
                                       PagedResourcesAssembler<MyPostCommentResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(commentService.onMyPosts(account, pageable)));
    }

    @PostMapping(value = "/community/posts/{postId}/comments", produces = MediaTypes.HAL_JSON_VALUE)
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @PathVariable Long postId,
                                    @Valid @RequestBody CommunityCommentRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(commentService.create(account, postId, request));
    }

    @PutMapping(value = "/community/comments/{commentId}", produces = MediaTypes.HAL_JSON_VALUE)
    public ResponseEntity<?> update(@CurrentUser Account account,
                                    @PathVariable Long commentId,
                                    @Valid @RequestBody CommunityCommentRequest request,
                                    BindingResult result) {
        reject(result);
        return ResponseEntity.ok().body(commentService.update(account, commentId, request));
    }

    /** 대댓글이 있으면 자리를 남기고(soft), 없으면 완전히 지운다 — 서비스가 판단한다. */
    @DeleteMapping("/community/comments/{commentId}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long commentId) {
        commentService.delete(account, commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/community/comments/{commentId}/like", produces = MediaTypes.HAL_JSON_VALUE)
    public ResponseEntity<?> like(@CurrentUser Account account, @PathVariable Long commentId) {
        return ResponseEntity.ok().body(commentService.like(account, commentId));
    }

    @DeleteMapping(value = "/community/comments/{commentId}/like", produces = MediaTypes.HAL_JSON_VALUE)
    public ResponseEntity<?> unlike(@CurrentUser Account account, @PathVariable Long commentId) {
        return ResponseEntity.ok().body(commentService.unlike(account, commentId));
    }

    private void reject(BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
    }
}
