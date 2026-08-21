package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;

/**
 * 닉네임 → 공개 프로필의 주인 찾기. 프로필 응답({@link BrandingService})과 공개 그리드
 * ({@link BrandingPostService})가 <b>같은 규칙</b>을 쓰도록 한 곳에 둔다 — 갈리면 프로필은 열리는데
 * 그리드만 400 이 나는 식으로 어긋난다.
 *
 * <h3>모든 계정에 프로필 페이지가 있다 (2026-08-21)</h3>
 * 프로필은 <b>계정의 성질</b>이지 따로 만들어야 생기는 물건이 아니다. 그래서 {@code account_branding}
 * <b>행이 없어도 200</b> 이고, 비어 있는 프로필이 나간다.
 *
 * <p><b>왜 이렇게 바꿨나.</b> 페이지에 실리는 값 중 이 행이 실제로 소유하는 건
 * tagline·bio·활동지역·공식기록·게시물뿐이다 — 닉네임·아바타는 {@code account}, 인증마크·자격은
 * {@code instructorapplication}, 강의 수는 {@code course} 소유라 <b>행이 없어도 빈 프로필은 완전하게
 * 계산된다.</b> 행은 "이 사람이 뭔가 적었다" 는 사실의 저장소일 뿐 페이지의 존재 조건이 아닌데,
 * 그 둘을 같은 것으로 취급해서 "안 적었으면 페이지도 없다" 가 되어 있었다.
 *
 * <p>실제로 새던 곳: <b>댓글만 단 유저</b>는 브랜딩 행이 생기지 않는데(글 작성은 upsert 하지만
 * {@code CommunityCommentService} 는 안 한다) 댓글 응답에는 닉네임이 실리고 그 계약이
 * "클라이언트가 {@code GET /instructors/{nickName}} 으로 그대로 쓴다" 였다 → <b>클릭하면 400</b>.
 * 강의 상세의 강사도 마찬가지였다.
 *
 * <p><b>가입 시 행을 미리 만드는 방식은 쓰지 않는다</b> — 전 계정에 빈 행 + 백필 마이그레이션 + 경합이
 * 따라오고, 무엇보다 "조회는 절대 생성하지 않는다" 는 규칙을 흔들 유혹이 생긴다. 파생으로 답하면 쓰기가
 * 전혀 없다.
 *
 * <h3>그래도 400 인 셋</h3>
 * <ol>
 *   <li>그 닉네임의 살아있는 계정이 없다(없는 닉네임·탈퇴)</li>
 *   <li>행이 있고 <b>유저가 직접 비공개로 내렸다</b>({@code isPublished=false}) — "안 적었다" 와 다르다</li>
 *   <li>상대가 나를 차단했다 — 판정은 호출부(뷰어를 아는 쪽)에서</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class PublicProfileResolver {

    private final AccountJpaRepo accountRepo;
    private final AccountBrandingJpaRepo brandingRepo;

    /**
     * @throws ResourceNotFoundException 없는 닉네임 · 탈퇴 계정 · 유저가 내린 비공개 프로필(전부 400)
     */
    public PublicProfile resolve(String nickName) {
        Account owner = accountRepo.findActiveByNickName(nickName).stream()
                .findFirst()
                .orElseThrow(ResourceNotFoundException::new);

        AccountBranding branding = brandingRepo.findByAccountId(owner.getId()).orElse(null);
        // 행이 없는 것(= 아직 아무것도 안 적음)과 유저가 내린 것은 다르다. 후자만 감춘다.
        if (branding != null && !branding.isPublished()) {
            throw new ResourceNotFoundException();
        }
        return new PublicProfile(owner, branding);
    }

    /** 주인 + (있다면) 프로필 행. {@code branding} 이 null 이면 아직 아무것도 적지 않은 계정이다. */
    @Getter
    public static class PublicProfile {
        private final Account owner;
        @Nullable
        private final AccountBranding branding;

        PublicProfile(Account owner, @Nullable AccountBranding branding) {
            this.owner = owner;
            this.branding = branding;
        }
    }
}
