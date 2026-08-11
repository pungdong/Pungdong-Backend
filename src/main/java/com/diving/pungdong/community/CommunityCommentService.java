package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.community.dto.CommunityAuthorResponse;
import com.diving.pungdong.community.dto.CommunityCommentRequest;
import com.diving.pungdong.community.dto.CommunityCommentResponse;
import com.diving.pungdong.community.dto.ReactionResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.notification.event.CommunityCommentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 댓글·대댓글·댓글 좋아요.
 *
 * <p><b>대댓글은 1단까지다.</b> 부모는 최상위 댓글만 될 수 있다 — 막지 않으면 스레드가 무한히 깊어져
 * 들여쓰기가 화면을 벗어나고, 디자인도 1-depth 로만 그려져 있다.
 *
 * <p><b>삭제는 두 갈래다.</b> 대댓글이 달린 댓글은 <b>soft delete</b>(자리를 남기고 본문만 가림) —
 * 물리 삭제하면 자식이 FK 로 끊기고 스레드 맥락이 사라진다. 자식이 없으면 <b>hard delete</b> —
 * 아무도 참조하지 않는 껍데기를 남길 이유가 없고, 남기면 "삭제된 댓글입니다" 만 늘어서 소음이 된다.
 *
 * <p>정렬은 <b>서버 고정 {@code createdAt ASC}</b>. 스레드는 위에서 아래로 대화가 흐르는 게 자연스럽고,
 * 디자인의 "최신순 ▾" 은 다른 옵션이 정의된 곳이 없어 정적 라벨로 처리하기로 했다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityCommentService {

    /** 삭제됐지만 자리를 지켜야 하는 댓글의 본문. 클라이언트가 흐리게 처리한다. */
    private static final String DELETED_BODY = "삭제된 댓글입니다.";

    private final CommunityCommentJpaRepo commentRepo;
    private final CommunityCommentLikeJpaRepo commentLikeRepo;
    private final CommunityPostJpaRepo postRepo;
    private final AccountJpaRepo accountRepo;
    private final CommunityAuthorComposer authorComposer;

    /**
     * 댓글 알림 발행. 이 트랜잭션 안에서 발행해야 한다 — outbox 리스너가 {@code MANDATORY} 라
     * 트랜잭션 밖 발행은 예외고, 안에서 발행해야 롤백 시 알림도 함께 취소된다.
     */
    private final ApplicationEventPublisher eventPublisher;

    /* ─── 조회 ───────────────────────────────────────────── */

    /**
     * 한 게시물의 댓글 스레드. <b>한 번에 다 읽어 메모리에서 트리로 조립</b>한다 — 최상위와 대댓글을
     * 나눠 조회하면 그 사이에 달린 댓글이 유실될 수 있고, 1-depth 라 크기가 작아 한 번이 낫다.
     */
    public List<CommunityCommentResponse> thread(Long postId, Account viewer) {
        requireVisiblePost(postId, viewer);

        List<CommunityComment> all = commentRepo.findThread(postId);
        if (all.isEmpty()) {
            return List.of();
        }

        List<Long> commentIds = all.stream().map(CommunityComment::getId).collect(Collectors.toList());
        Map<Long, Long> likeCounts = toCountMap(commentLikeRepo.countByCommentIds(commentIds));
        Set<Long> likedByMe = viewer == null
                ? Set.of()
                : new HashSet<>(commentLikeRepo.findLikedCommentIds(viewer.getId(), commentIds));
        Map<Long, CommunityAuthorResponse> authors = authorComposer.compose(
                all.stream().map(CommunityComment::getAccount).collect(Collectors.toList()));

        // 대댓글을 부모별로 모아두고, 최상위만 순회하며 붙인다.
        Map<Long, List<CommunityComment>> repliesByParent = all.stream()
                .filter(c -> !c.isTopLevel())
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return all.stream()
                .filter(CommunityComment::isTopLevel)
                .map(parent -> toResponse(parent, authors, likeCounts, likedByMe, viewer,
                        repliesByParent.getOrDefault(parent.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /* ─── 쓰기 ───────────────────────────────────────────── */

    @Transactional
    public CommunityCommentResponse create(Account currentUser, Long postId, CommunityCommentRequest request) {
        BrandingPost post = requireVisiblePost(postId, currentUser);
        Account me = loadAccount(currentUser);

        CommunityComment parent = null;
        if (request.getParentCommentId() != null) {
            parent = commentRepo.findById(request.getParentCommentId())
                    .orElseThrow(ResourceNotFoundException::new);
            if (!Objects.equals(parent.getPost().getId(), postId)) {
                // 다른 글의 댓글을 부모로 지정 — 존재 자체를 알려주지 않는다.
                throw new ResourceNotFoundException();
            }
            if (!parent.isTopLevel()) {
                throw new BadRequestException("대댓글에는 답글을 달 수 없어요.");
            }
            if (parent.isDeleted()) {
                throw new BadRequestException("삭제된 댓글에는 답글을 달 수 없어요.");
            }
        }

        CommunityComment saved = commentRepo.save(CommunityComment.builder()
                .post(post).parent(parent).account(me).body(request.getBody()).build());
        notifyRecipient(saved, post, parent, me);
        return toResponse(saved, authorComposer.compose(List.of(me)), Map.of(), Set.of(), currentUser, List.of());
    }

    @Transactional
    public CommunityCommentResponse update(Account currentUser, Long commentId, CommunityCommentRequest request) {
        CommunityComment comment = requireMine(commentId, currentUser.getId());
        if (comment.isDeleted()) {
            throw new BadRequestException("삭제된 댓글은 수정할 수 없어요.");
        }
        comment.setBody(request.getBody());
        return toResponse(comment, authorComposer.compose(List.of(comment.getAccount())),
                Map.of(commentId, commentLikeRepo.countByCommentId(commentId)),
                likedSet(currentUser, commentId), currentUser, List.of());
    }

    /**
     * 삭제 — 대댓글이 있으면 자리를 남기고(soft), 없으면 완전히 지운다(hard).
     */
    @Transactional
    public void delete(Account currentUser, Long commentId) {
        CommunityComment comment = requireMine(commentId, currentUser.getId());

        if (commentRepo.existsByParentId(commentId)) {
            comment.setDeleted(true);
            comment.setBody(DELETED_BODY);
            return;
        }
        commentLikeRepo.deleteByCommentId(commentId);
        commentRepo.delete(comment);
    }

    /* ─── 댓글 좋아요 ─────────────────────────────────────── */

    @Transactional
    public ReactionResponse like(Account currentUser, Long commentId) {
        CommunityComment comment = requireLikable(commentId);
        Account me = loadAccount(currentUser);

        if (commentLikeRepo.findByCommentIdAndAccountId(commentId, me.getId()).isEmpty()) {
            try {
                commentLikeRepo.save(CommunityCommentLike.builder().comment(comment).account(me).build());
            } catch (DataIntegrityViolationException alreadyLiked) {
                // 경쟁 요청이 먼저 넣었다 — 결과가 같으니 에러가 아니다.
            }
        }
        return ReactionResponse.builder()
                .count(commentLikeRepo.countByCommentId(commentId)).active(true).build();
    }

    @Transactional
    public ReactionResponse unlike(Account currentUser, Long commentId) {
        requireLikable(commentId);
        commentLikeRepo.findByCommentIdAndAccountId(commentId, currentUser.getId())
                .ifPresent(commentLikeRepo::delete);
        return ReactionResponse.builder()
                .count(commentLikeRepo.countByCommentId(commentId)).active(false).build();
    }

    /* ─── 알림 ───────────────────────────────────────────── */

    /**
     * 댓글·답글 알림을 <b>한 사람에게만</b> 보낸다 — 답글이면 부모 댓글 작성자, 아니면 글 작성자.
     *
     * <p><b>답글일 때 글 작성자에게도 보내지 않는 이유</b>: 스레드가 길어지면 글 작성자가 모든 답글을
     * 다 받게 돼 소음이 된다. 인앱 알림함이 없어 푸시가 유일한 채널이라 더 조심해야 한다.
     *
     * <p><b>자기 자신에게는 보내지 않는다.</b> 파이프라인에 자기알림 필터가 없어서 여기서 걸러야 하고,
     * 내 글에 내가 댓글 다는 건 흔한 동작이다.
     *
     * <p>발행은 이 트랜잭션 안에서 일어난다 — {@code NotificationOutboxWriter} 리스너가
     * {@code MANDATORY} 라 트랜잭션 밖에서 발행하면 예외가 난다. 덕분에 <b>댓글 저장이 롤백되면
     * 알림도 함께 롤백</b>돼 유령 알림이 생기지 않는다.
     */
    private void notifyRecipient(CommunityComment saved, BrandingPost post,
                                 CommunityComment parent, Account actor) {
        Account recipient = parent != null
                ? parent.getAccount()
                : post.getBranding().getAccount();

        if (Objects.equals(recipient.getId(), actor.getId())) {
            return;
        }
        eventPublisher.publishEvent(CommunityCommentEvent.builder()
                .recipientAccountId(recipient.getId())
                .postId(post.getId())
                .commentId(saved.getId())
                .actorNickName(actor.getNickName())
                .postTitle(post.getTitle())
                .reply(parent != null)
                .build());
    }

    /* ─── 내부 ───────────────────────────────────────────── */

    private CommunityCommentResponse toResponse(CommunityComment comment,
                                                Map<Long, CommunityAuthorResponse> authors,
                                                Map<Long, Long> likeCounts,
                                                Set<Long> likedByMe,
                                                Account viewer,
                                                List<CommunityComment> replies) {
        return CommunityCommentResponse.builder()
                .id(comment.getId())
                .author(authors.get(comment.getAccount().getId()))
                .body(comment.getBody())
                .deleted(comment.isDeleted())
                .createdAt(comment.getCreatedAt())
                .likeCount(likeCounts.getOrDefault(comment.getId(), 0L))
                .likedByMe(likedByMe.contains(comment.getId()))
                .mine(viewer != null && Objects.equals(comment.getAccount().getId(), viewer.getId()))
                .replies(replies.stream()
                        .map(reply -> toResponse(reply, authors, likeCounts, likedByMe, viewer, List.of()))
                        .collect(Collectors.toList()))
                .replyCount(replies.size())
                .build();
    }

    /**
     * 댓글을 달거나 볼 수 있는 글인가. 공개 글이거나 <b>내 글</b>이어야 한다 — 숨긴 남의 글에 댓글을
     * 달 수 있으면 존재를 알려주는 셈이다(anti-IDOR).
     */
    private BrandingPost requireVisiblePost(Long postId, Account viewer) {
        return postRepo.findVisibleInFeed(postId)
                .or(() -> viewer == null ? Optional.empty() : postRepo.findMine(postId, viewer.getId()))
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** 삭제된 댓글에는 좋아요를 걸 수 없다 — 자리만 남은 껍데기다. */
    private CommunityComment requireLikable(Long commentId) {
        CommunityComment comment = commentRepo.findById(commentId)
                .orElseThrow(ResourceNotFoundException::new);
        if (comment.isDeleted()) {
            throw new BadRequestException("삭제된 댓글에는 좋아요를 누를 수 없어요.");
        }
        return comment;
    }

    private CommunityComment requireMine(Long commentId, Long accountId) {
        return commentRepo.findByIdAndAccountId(commentId, accountId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private Set<Long> likedSet(Account viewer, Long commentId) {
        if (viewer == null) {
            return Set.of();
        }
        return new HashSet<>(commentLikeRepo.findLikedCommentIds(viewer.getId(), List.of(commentId)));
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }
}
