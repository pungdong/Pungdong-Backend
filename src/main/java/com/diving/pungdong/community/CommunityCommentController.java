package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.community.dto.CommunityCommentRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
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
 */
@RestController
@RequiredArgsConstructor
public class CommunityCommentController {

    private final CommunityCommentService commentService;

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
