package com.diving.pungdong.usecase;

import com.diving.pungdong.account.*;
import com.diving.pungdong.block.AccountBlockJpaRepo;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.branding.BrandingPostJpaRepo;
import com.diving.pungdong.course.*;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유저 차단 — 애플 App Store 심사 가이드라인 1.2(UGC)가 요구하는 "학대적 사용자 차단".
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. B* 는 전부 차단이다.
 *
 * <p>이 피처에서 가장 틀리기 쉬운 건 <b>필터를 한 경로만 거는 것</b>이다. 커뮤니티 피드는 쿼리 경로가
 * 셋이고(최신순 Specification · 인기순 전용쿼리 · 같이가요 전용쿼리) 하나만 고치면 그 탭에서만 차단이
 * 새어 나간다 — B1~B3 이 세 경로를 각각 못 박는다. 그리고 <b>차단은 커뮤니티 표면에서만 동작한다</b>:
 * 거래 관계(강의·수강·결제)는 건드리지 않는다는 게 정책이고 B12 가 그걸 지킨다.
 *
 * <p>정책 전문은 {@code docs/features/moderation.md}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlockUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired AccountBlockJpaRepo blockRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired BrandingPostJpaRepo postRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired com.diving.pungdong.community.CommunityPostMatchJpaRepo matchRepo;
    @Autowired com.diving.pungdong.community.CommunityCommentJpaRepo commentRepo;
    @Autowired com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo applicationRepo;

    /** 삭제 순서는 FK 방향의 역순. 차단 행은 계정을 참조하므로 계정보다 먼저 지운다. */
    @AfterEach
    void cleanUp() {
        blockRepo.deleteAll();
        applicationRepo.deleteAll();
        matchRepo.deleteAll();
        commentRepo.findAll().stream()
                .filter(comment -> !comment.isTopLevel())
                .forEach(commentRepo::delete);
        commentRepo.deleteAll();
        postRepo.deleteAll();
        brandingRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ── fixture ─────────────────────────────────────────── */

    private Account account(String email, String nickName) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).isDeleted(false).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private long createPost(Account author, String category, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + category + "\",\"title\":\"" + title
                                + "\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 같이가요 글 — 전용 쿼리 경로(findMatchFeed)를 타게 하려면 모집 정보가 있어야 한다. */
    private void createMatchPost(Account author, String title) throws Exception {
        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"" + title + "\",\"body\":\"본문\","
                                + "\"match\":{\"meetDate\":\"2027-03-01\",\"capacity\":4,"
                                + "\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isOk());
    }

    private long comment(Account author, long postId, Long parentId) throws Exception {
        String body = parentId == null
                ? "{\"body\":\"댓글\"}"
                : "{\"body\":\"대댓글\",\"parentCommentId\":" + parentId + "}";
        MvcResult result = mockMvc.perform(post("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void block(Account me, Account target) throws Exception {
        mockMvc.perform(post("/blocks")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickName\":\"" + target.getNickName() + "\"}"))
                .andExpect(status().isOk());
    }

    private URI profile(String nickName) {
        return URI.create("/instructors/" + URLEncoder.encode(nickName, StandardCharsets.UTF_8));
    }

    private void approveAsInstructor(Account account) {
        applicationRepo.save(com.diving.pungdong.instructorapplication.InstructorApplication.builder()
                .account(account)
                .disciplineCode("FREEDIVING")
                .status(com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED)
                .reviewedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .build());
    }

    /* ════════════════ B — 차단 ════════════════ */

    @Test
    @DisplayName("B1: 차단하면 그 사람의 글이 최신순 피드에서 사라진다 (totalElements 도 함께 줄어든다)")
    void blocked_disappearsFromLatestFeed() throws Exception {
        Account me = account("b1@c.com", "diverB1");
        Account other = account("b1o@c.com", "trollB1");
        createPost(other, "QNA", "시끄러운 글");
        createPost(me, "QNA", "내 글");

        block(me, other);

        mockMvc.perform(get("/community/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                // 클라이언트가 지우는 게 아니라 서버가 거른다 — 총 개수까지 정확해야 페이징이 성립한다.
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("내 글"));
    }

    @Test
    @DisplayName("B2: 인기순 피드에서도 사라진다 (Specification 이 아니라 전용 쿼리를 타는 경로)")
    void blocked_disappearsFromPopularFeed() throws Exception {
        Account me = account("b2@c.com", "diverB2");
        Account other = account("b2o@c.com", "trollB2");
        createPost(other, "QNA", "시끄러운 글");
        createPost(me, "QNA", "내 글");

        block(me, other);

        mockMvc.perform(get("/community/posts?sort=POPULAR")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("내 글"));
    }

    @Test
    @DisplayName("B3: 같이가요 피드에서도 사라진다 (일정 임박순 전용 쿼리 경로)")
    void blocked_disappearsFromMatchFeed() throws Exception {
        Account me = account("b3@c.com", "diverB3");
        Account other = account("b3o@c.com", "trollB3");
        createMatchPost(other, "같이 가실 분");
        createMatchPost(me, "내 모집");

        block(me, other);

        mockMvc.perform(get("/community/posts?category=MATCH")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("내 모집"));
    }

    @Test
    @DisplayName("B4: 차단은 양방향이다 — 차단당한 쪽에서도 내 글이 보이지 않는다")
    void block_hidesBothWays() throws Exception {
        Account me = account("b4@c.com", "diverB4");
        Account other = account("b4o@c.com", "trollB4");
        createPost(me, "QNA", "내 글");

        block(me, other);

        // 상대는 차단당한 사실을 통보받지 않는다. 다만 내 글이 그의 피드에서 사라진다.
        mockMvc.perform(get("/community/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("B5: 차단한 사람의 댓글은 스레드에서 빠지고 댓글 수도 같은 기준으로 줄어든다")
    void blocked_commentsAndCountDropTogether() throws Exception {
        Account me = account("b5@c.com", "diverB5");
        Account other = account("b5o@c.com", "trollB5");
        long postId = createPost(me, "QNA", "내 글");
        comment(other, postId, null);
        comment(me, postId, null);

        block(me, other);

        // "댓글 3인데 2개 보임" 은 이 레포가 명시적으로 버그라 부르는 상태다 — 목록과 수가 같아야 한다.
        mockMvc.perform(get("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.comments.length()").value(1));

        mockMvc.perform(get("/community/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(1));
    }

    @Test
    @DisplayName("B6: 차단한 사람의 댓글에 달린 답글도 함께 사라진다 (부모가 없으면 붙을 자리가 없다)")
    void blocked_repliesDropWithParent() throws Exception {
        Account me = account("b6@c.com", "diverB6");
        Account other = account("b6o@c.com", "trollB6");
        Account third = account("b6t@c.com", "diverB6t");
        long postId = createPost(me, "QNA", "내 글");
        long parentId = comment(other, postId, null);
        comment(third, postId, parentId);

        block(me, other);

        mockMvc.perform(get("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());

        mockMvc.perform(get("/community/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.commentCount").value(0));
    }

    @Test
    @DisplayName("B7: 차단한 사람의 글은 상세 URL 로도 열리지 않는다 (딥링크 우회 차단)")
    void blocked_detailIsHidden() throws Exception {
        Account me = account("b7@c.com", "diverB7");
        Account other = account("b7o@c.com", "trollB7");
        long postId = createPost(other, "QNA", "시끄러운 글");

        block(me, other);

        mockMvc.perform(get("/community/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B8: 차단한 사람의 글에는 좋아요를 누를 수 없다 (id 만 알아도 반응이 걸리면 필터가 뚫린다)")
    void blocked_reactionIsRejected() throws Exception {
        Account me = account("b8@c.com", "diverB8");
        Account other = account("b8o@c.com", "trollB8");
        long postId = createPost(other, "QNA", "시끄러운 글");

        block(me, other);

        mockMvc.perform(post("/community/posts/" + postId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B9: 중복 차단은 200 멱등이다 (행은 하나) · 자기 자신 차단은 400")
    void block_isIdempotent_andSelfBlockRejected() throws Exception {
        Account me = account("b9@c.com", "diverB9");
        Account other = account("b9o@c.com", "trollB9");

        block(me, other);
        block(me, other);
        assertThat(blockRepo.count()).isEqualTo(1);

        mockMvc.perform(post("/blocks")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickName\":\"" + me.getNickName() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B10: 차단을 해제하면 다시 보인다 · 차단한 적 없어도 해제는 204 (멱등)")
    void unblock_restoresVisibility() throws Exception {
        Account me = account("b10@c.com", "diverB10");
        Account other = account("b10o@c.com", "trollB10");
        createPost(other, "QNA", "시끄러운 글");

        block(me, other);
        mockMvc.perform(delete("/blocks/" + other.getNickName())
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/community/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        // 이미 해제된 상태에서 한 번 더 — 결과 상태가 같으므로 에러가 아니다.
        mockMvc.perform(delete("/blocks/" + other.getNickName())
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("B11: 내가 차단한 사람의 프로필은 열리되 blockedByMe=true 이고 글 그리드는 비어 있다")
    void blockedProfile_staysOpenForUnblocking() throws Exception {
        Account me = account("b11@c.com", "diverB11");
        Account other = account("b11o@c.com", "trollB11");
        approveAsInstructor(other);
        createPost(other, "QNA", "차단될 글");
        // 프로필이 발행돼 있어야 공개 조회가 열린다.
        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk());

        block(me, other);

        // 여기가 유일한 해제 동선이라 400 으로 막으면 되돌릴 방법이 없어진다.
        mockMvc.perform(get(profile(other.getNickName()))
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedByMe").value(true));

        mockMvc.perform(get(profile(other.getNickName()) + "/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("B12: 나를 차단한 사람의 프로필은 400 이다 — 차단당한 사실을 알려주지 않는다")
    void blockedByThem_profileIsHidden() throws Exception {
        Account me = account("b12@c.com", "diverB12");
        Account other = account("b12o@c.com", "trollB12");
        approveAsInstructor(other);
        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk());

        block(other, me);

        mockMvc.perform(get(profile(other.getNickName()))
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B13: 차단한 강사는 추천 강사에서 빠진다 (totalCount 도 줄어든다)")
    void blocked_droppedFromSuggestedInstructors() throws Exception {
        Account me = account("b13@c.com", "diverB13");
        Account instructor = account("b13i@c.com", "coachB13");
        approveAsInstructor(instructor);
        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk());

        block(me, instructor);

        mockMvc.perform(get("/instructors/suggested")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("B14: 차단 목록에 상대가 실린다 — 설정의 '차단 관리' 화면")
    void myBlocks_listsBlockedAccounts() throws Exception {
        Account me = account("b14@c.com", "diverB14");
        Account other = account("b14o@c.com", "trollB14");

        block(me, other);

        mockMvc.perform(get("/blocks").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.blockedAccountResponseList[0].nickName")
                        .value("trollB14"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("B15: 차단은 거래를 끊지 않는다 — 차단한 강사의 강의는 둘러보기에 그대로 뜬다")
    void block_doesNotTouchCommerce() throws Exception {
        Account me = account("b15@c.com", "diverB15");
        Account instructor = account("b15i@c.com", "coachB15");
        approveAsInstructor(instructor);
        courseRepo.save(Course.builder()
                .instructor(instructor).title("문섬 어드밴스드").kind(CourseKind.CERTIFICATION)
                .disciplineCode("FREEDIVING").totalRounds(1).price(680000)
                .status(CourseStatus.OPEN).build());

        block(me, instructor);

        // 차단 범위는 커뮤니티 표면뿐이다. 강의·수강·결제까지 끊으면 이미 돈이 오간 관계가 깨진다.
        mockMvc.perform(get("/courses/browse?disciplineCode=FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("B16: 비로그인 피드는 아무것도 걸리지 않는다 (차단 관계 자체가 없다)")
    void anonymousFeed_isUnfiltered() throws Exception {
        Account me = account("b16@c.com", "diverB16");
        Account other = account("b16o@c.com", "trollB16");
        createPost(other, "QNA", "시끄러운 글");

        block(me, other);

        mockMvc.perform(get("/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("B17: 차단한 사람의 댓글은 '내 글에 달린 댓글' 목록에서도 빠진다 (카드의 댓글 수와 같은 기준)")
    void blocked_droppedFromCommentsOnMyPosts() throws Exception {
        Account me = account("b17@c.com", "diverB17");
        Account other = account("b17o@c.com", "trollB17");
        Account third = account("b17t@c.com", "diverB17t");
        long postId = createPost(me, "QNA", "내 글");
        comment(other, postId, null);
        comment(third, postId, null);

        block(me, other);

        // 목록만 거르고 수를 안 거르면 프로필 카드가 "댓글 2" 를 띄우고 미리보기엔 1건만 뜬다.
        mockMvc.perform(get("/community/posts/me/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.comments[0].author.nickName").value("diverB17t"));
    }
}
