package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.community.dto.ReactionResponse;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요·북마크 토글.
 *
 * <p><b>전부 멱등이다.</b> 마커 테이블의 {@code (대상, 계정)} UNIQUE 덕에 같은 요청을 두 번 보내도 행은
 * 하나다 — 재시도나 연타로 카운트가 부풀지 않는다. <b>동시</b> 요청도 마찬가지다: 삽입을
 * {@link IdempotentInsert} 로 격리해 제약 위반이 이 트랜잭션을 오염시키지 않는다(격리 없이 catch 만
 * 하면 뒤이은 카운트 조회에서 500 이 난다). 응답은 항상 <b>갱신된 카운트 + 내 상태</b>라
 * 클라이언트가 낙관적 업데이트를 해도 이 값으로 덮어쓰면 수렴한다.
 *
 * <p>대상 게시물은 <b>피드에 보이는 글</b>이어야 한다. 숨김·미노출 글에 좋아요를 걸 수 있으면 존재를
 * 알려주는 셈이라(anti-IDOR) 400 으로 막는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityReactionService {

    private final CommunityPostJpaRepo postRepo;
    private final CommunityPostLikeJpaRepo likeRepo;
    private final CommunityPostBookmarkJpaRepo bookmarkRepo;
    private final AccountJpaRepo accountRepo;
    /** 제약 위반이 이 트랜잭션을 오염시키지 않도록 삽입만 새 트랜잭션에서 돌린다. */
    private final IdempotentInsert idempotentInsert;

    @Transactional
    public ReactionResponse like(Account currentUser, Long postId) {
        BrandingPost post = requireVisible(postId);
        Account me = loadAccount(currentUser);

        // 이미 있으면 새로 만들지 않는다. 그럼에도 동시에 두 요청이 들어오면 UNIQUE 가 최종 방어선이라
        // 제약 위반을 "이미 눌린 상태" 로 흡수한다 — 사용자 입장에서 결과가 같으니 에러가 아니다.
        if (likeRepo.findByPostIdAndAccountId(postId, me.getId()).isEmpty()) {
            try {
                idempotentInsert.insert(likeRepo, CommunityPostLike.builder().post(post).account(me).build());
            } catch (DataIntegrityViolationException alreadyLiked) {
                // no-op — 경쟁 요청이 먼저 넣었다. 결과가 같으니 에러가 아니다.
            }
        }
        return ReactionResponse.builder()
                .count(likeRepo.countByPostId(postId)).active(true).build();
    }

    @Transactional
    public ReactionResponse unlike(Account currentUser, Long postId) {
        requireVisible(postId);
        likeRepo.findByPostIdAndAccountId(postId, currentUser.getId()).ifPresent(likeRepo::delete);
        return ReactionResponse.builder()
                .count(likeRepo.countByPostId(postId)).active(false).build();
    }

    @Transactional
    public ReactionResponse bookmark(Account currentUser, Long postId) {
        BrandingPost post = requireVisible(postId);
        Account me = loadAccount(currentUser);

        if (bookmarkRepo.findByPostIdAndAccountId(postId, me.getId()).isEmpty()) {
            try {
                idempotentInsert.insert(bookmarkRepo,
                        CommunityPostBookmark.builder().post(post).account(me).build());
            } catch (DataIntegrityViolationException alreadyBookmarked) {
                // no-op — 위와 같다.
            }
        }
        return ReactionResponse.builder()
                .count(bookmarkRepo.countByPostId(postId)).active(true).build();
    }

    @Transactional
    public ReactionResponse unbookmark(Account currentUser, Long postId) {
        requireVisible(postId);
        bookmarkRepo.findByPostIdAndAccountId(postId, currentUser.getId()).ifPresent(bookmarkRepo::delete);
        return ReactionResponse.builder()
                .count(bookmarkRepo.countByPostId(postId)).active(false).build();
    }

    /** 피드에 보이는 글만 반응 대상이다. 숨김·없는 글은 400(존재 숨김). */
    private BrandingPost requireVisible(Long postId) {
        return postRepo.findVisibleInFeed(postId).orElseThrow(ResourceNotFoundException::new);
    }

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }
}
