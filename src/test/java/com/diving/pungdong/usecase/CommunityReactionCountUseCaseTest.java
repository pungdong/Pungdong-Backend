package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.branding.BrandingPostJpaRepo;
import com.diving.pungdong.community.CommunityCommentJpaRepo;
import com.diving.pungdong.community.CommunityCommentLikeJpaRepo;
import com.diving.pungdong.community.CommunityPostBookmarkJpaRepo;
import com.diving.pungdong.community.CommunityPostLikeJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커뮤니티 — 리액션 응답의 {@code count} 가 <b>이번 변경이 반영된 값</b>인지.
 *
 * <p><b>왜 별도 클래스인가</b>: 이 버그(2026-08-17, staging 실측)는 MySQL(InnoDB) 기본 격리인
 * <b>REPEATABLE READ</b> 에서만 난다 — 삽입은 {@code IdempotentInsert} 가 REQUIRES_NEW 로 커밋하는데,
 * 바깥 트랜잭션은 첫 SELECT 시점의 스냅샷을 끝까지 봐서 그 행이 {@code count} 에 안 잡혔다(POST 만
 * "내 것 빠진 값", DELETE 는 정확 — 비대칭). H2 기본은 READ COMMITTED 라 {@link CommunityUseCaseTest}
 * 의 K1/C6 는 통과했고 그래서 못 잡았다. 격리 수준을 <b>테스트 전역</b>으로 핀하지 않는 건 H2 2.1.214 가
 * REPEATABLE READ + {@code ON DELETE CASCADE}(G1 게시물 삭제) 에서 내부 NPE(50000) 를 내기 때문이다 —
 * H2 버그라 이 클래스만 자기 컨텍스트로 격리 수준을 올린다.
 */
@SpringBootTest(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunityReactionCountUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired BrandingPostJpaRepo postRepo;
    @Autowired CommunityPostLikeJpaRepo likeRepo;
    @Autowired CommunityPostBookmarkJpaRepo bookmarkRepo;
    @Autowired CommunityCommentJpaRepo commentRepo;
    @Autowired CommunityCommentLikeJpaRepo commentLikeRepo;

    /** 이 클래스가 만든 계정만 지운다 — H2 mem DB 는 다른 테스트 컨텍스트와 공유라 남의 잔여 행을 건드리지 않는다. */
    private final List<Account> created = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        // FK 역순: 리액션 → 댓글 → 게시물 → 브랜딩(글 작성 시 계정당 1행 자동 생성) → 계정.
        likeRepo.deleteAll();
        bookmarkRepo.deleteAll();
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        postRepo.deleteAll();
        brandingRepo.deleteAll();
        accountRepo.deleteAll(created);
        created.clear();
    }

    @Test
    @DisplayName("리액션 응답의 count 는 이번 변경이 반영된 값이다 — 좋아요·북마크·댓글 좋아요 × POST/DELETE (독립 조회와 일치)")
    void reactionCount_reflectsThisChange() throws Exception {
        // 남이 먼저 눌러 둔 상태(1)에서 시작해 "0→1" 우연 일치가 아니라 "1→2" 로 잠근다.
        Account other = account("rc-other@c.com", "diverRC1");
        Account me = account("rc-me@c.com", "diverRC2");
        long postId = createPost(other);
        long commentId = comment(other, postId);
        for (String path : List.of("/community/posts/" + postId + "/like",
                                   "/community/posts/" + postId + "/bookmark",
                                   "/community/comments/" + commentId + "/like")) {
            mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                    .andExpect(status().isOk());
        }

        assertReaction(post("/community/posts/" + postId + "/like"), me, 2, true);
        assertThat(likeRepo.countByPostId(postId)).isEqualTo(2);
        assertReaction(post("/community/posts/" + postId + "/like"), me, 2, true);      // 멱등 재요청 — 불변
        assertReaction(delete("/community/posts/" + postId + "/like"), me, 1, false);
        assertThat(likeRepo.countByPostId(postId)).isEqualTo(1);

        assertReaction(post("/community/posts/" + postId + "/bookmark"), me, 2, true);
        assertThat(bookmarkRepo.countByPostId(postId)).isEqualTo(2);
        assertReaction(post("/community/posts/" + postId + "/bookmark"), me, 2, true);
        assertReaction(delete("/community/posts/" + postId + "/bookmark"), me, 1, false);
        assertThat(bookmarkRepo.countByPostId(postId)).isEqualTo(1);

        assertReaction(post("/community/comments/" + commentId + "/like"), me, 2, true);
        assertThat(commentLikeRepo.countByCommentId(commentId)).isEqualTo(2);
        assertReaction(post("/community/comments/" + commentId + "/like"), me, 2, true);
        assertReaction(delete("/community/comments/" + commentId + "/like"), me, 1, false);
        assertThat(commentLikeRepo.countByCommentId(commentId)).isEqualTo(1);
    }

    /* ── fixture ─────────────────────────────────────────── */

    private void assertReaction(MockHttpServletRequestBuilder req, Account who, long count, boolean active)
            throws Exception {
        mockMvc.perform(req.header(HttpHeaders.AUTHORIZATION, tokenFor(who)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(count))
                .andExpect(jsonPath("$.active").value(active));
    }

    private Account account(String email, String nickName) {
        Account saved = accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).isDeleted(false).build());
        created.add(saved);
        return saved;
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private long createPost(Account author) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"리액션 대상\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result);
    }

    private long comment(Account author, long postId) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"댓글\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result);
    }

    private long idOf(MvcResult result) throws Exception {
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
