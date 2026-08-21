package com.diving.pungdong.block;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.block.dto.BlockedAccountResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.persistence.IdempotentInsert;
import com.diving.pungdong.global.persistence.PageClamp;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/**
 * 유저 차단 — 접수·해제·목록, 그리고 다른 도메인이 쓰는 <b>판정</b>.
 *
 * <p><b>이 서비스는 {@code account} 만 참조한다.</b> 커뮤니티·브랜딩 양쪽이 차단을 읽어야 하는데
 * {@code branding} 은 {@code community} 를 import 할 수 없어(단방향 규칙), 차단을 커뮤니티에 두면
 * 브랜딩이 쓸 수 없다 — {@code content_report} 가 정확히 그 문제를 겪었다
 * ({@code docs/features/post-surfaces.md}). 그래서 별도 패키지이고 의존은 한 방향뿐이다.
 *
 * <p><b>목록 필터에 이 서비스를 쓰지 말 것.</b> 차단 대상 id 를 받아 메모리에서 걸러내면 페이지가
 * 짧아지고 {@code totalElements} 가 거짓이 된다. 페이징되는 조회는 쿼리 안의 {@code exists} 서브쿼리로
 * 거른다({@code CommunityPostJpaRepo.BLOCK_FILTER}). 여기 판정 메서드는 단건 경로와 페이징 없는
 * 목록(댓글 스레드·추천 강사)만 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

    private final AccountBlockJpaRepo blockRepo;
    private final AccountJpaRepo accountRepo;
    /** UNIQUE 위반을 별도 트랜잭션에 가둔다 — 동시 중복 차단이 500 이 되지 않게. */
    private final IdempotentInsert idempotentInsert;

    /* ─── 판정 (다른 도메인이 쓴다) ───────────────────────── */

    /**
     * 두 계정 사이에 <b>어느 방향으로든</b> 차단이 있나. 상세·반응·댓글 작성 경로의 가드.
     * 둘 중 하나라도 null 이거나 같은 계정이면 false.
     */
    public boolean isBlockedBetween(Long one, Long other) {
        if (one == null || other == null || Objects.equals(one, other)) {
            return false;
        }
        return blockRepo.existsBetween(one, other);
    }

    /** 내가 저 계정을 차단했나 — 프로필의 "차단 해제" 버튼 상태. */
    public boolean hasBlocked(Long blockerId, Long blockedId) {
        if (blockerId == null || blockedId == null) {
            return false;
        }
        return blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    /**
     * 뷰어와 차단 관계인 상대 계정 id 전부(양방향). <b>페이징 없는</b> 목록을 메모리에서 거를 때만 쓴다.
     * 뷰어가 null 이면 빈 집합 — 비로그인은 차단 관계가 없다.
     */
    public Set<Long> relatedAccountIds(Long viewerId) {
        if (viewerId == null) {
            return Set.of();
        }
        return Set.copyOf(blockRepo.findRelatedAccountIds(viewerId));
    }

    /* ─── 쓰기 ───────────────────────────────────────────── */

    /**
     * 차단. <b>중복은 200 멱등</b>이다 — 이미 차단한 사람을 다시 차단해도 사용자 입장에선 "차단됨" 이
     * 맞는 결과라 4xx 로 돌려주지 않는다(신고·좋아요와 같은 규칙).
     *
     * <p>자기 자신은 400. 없는 닉네임도 400(존재 숨김) — 이 레포는 404 를 쓰지 않는다.
     */
    @Transactional
    public BlockedAccountResponse block(Account currentUser, String nickName) {
        Account me = loadAccount(currentUser);
        Account target = accountRepo.findByNickName(nickName).orElseThrow(ResourceNotFoundException::new);

        if (Objects.equals(target.getId(), me.getId())) {
            throw new BadRequestException("자기 자신은 차단할 수 없어요.");
        }

        return blockRepo.findByBlockerIdAndBlockedId(me.getId(), target.getId())
                .map(this::toResponse)
                .orElseGet(() -> insertOrReread(me, target));
    }

    /**
     * 해제. 차단돼 있지 않아도 <b>204</b> 다 — "차단이 아닌 상태" 라는 결과가 같으므로 에러가 아니다.
     */
    @Transactional
    public void unblock(Account currentUser, String nickName) {
        Account me = loadAccount(currentUser);
        accountRepo.findByNickName(nickName)
                .flatMap(target -> blockRepo.findByBlockerIdAndBlockedId(me.getId(), target.getId()))
                .ifPresent(blockRepo::delete);
    }

    /** 차단 관리 화면 — 내가 차단한 사람 목록(최근 차단순). */
    public Page<BlockedAccountResponse> myBlocks(Account currentUser, Pageable pageable) {
        return blockRepo.findMine(currentUser.getId(), PageClamp.fixed(pageable)).map(this::toResponse);
    }

    /* ─── 내부 ───────────────────────────────────────────── */

    /**
     * 조회와 삽입 사이에 같은 사람의 두 번째 요청이 끼면 UNIQUE 가 걸린다. 삽입을 별도 트랜잭션에
     * 가두지 않고 여기서 잡으면 이 트랜잭션이 rollback-only 로 오염돼 결국 500 이 난다
     * ({@link IdempotentInsert} Javadoc).
     */
    private BlockedAccountResponse insertOrReread(Account me, Account target) {
        AccountBlock block = AccountBlock.builder().blocker(me).blocked(target).build();
        try {
            idempotentInsert.insert(blockRepo, block);
        } catch (DataIntegrityViolationException alreadyBlocked) {
            return blockRepo.findByBlockerIdAndBlockedId(me.getId(), target.getId())
                    .map(this::toResponse)
                    .orElseThrow(ResourceNotFoundException::new);
        }
        return toResponse(block);
    }

    private BlockedAccountResponse toResponse(AccountBlock block) {
        Account blocked = block.getBlocked();
        return BlockedAccountResponse.builder()
                .nickName(blocked.getNickName())
                .avatarUrl(ProfilePhoto.displayUrlOf(blocked))
                .blockedAt(block.getCreatedAt())
                .build();
    }

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }
}
