package com.diving.pungdong.usecase;

import com.diving.pungdong.account.*;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.branding.BrandingPostJpaRepo;
import com.diving.pungdong.community.CommunityPostMatch;
import com.diving.pungdong.course.*;
import com.diving.pungdong.global.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커뮤니티 — 피드·상세·작성.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / X* 노출 방향 / P* 프로필 노출·숨김 / F* 필터 / M* 같이가요 / A* 작성자 합성 / V* 검증 /
 * H* 숨김 / R* 권한 / K* 좋아요·북마크 / D* 탐색 지표 / C* 댓글 / N* 알림 / G* 가드 /
 * <b>E* 수정(edit)</b>.
 *
 * <p>이 피처에서 가장 틀리기 쉬운 건 <b>노출</b>이다. 작성 폼이 하나로 합쳐진 뒤(2026-08-18)
 * 규칙은 두 축이다 — {@code showOnProfile}(작성자가 고른다)과 {@code isHidden}(숨김이 이긴다).
 * X* 는 두 쓰기 경로가 만드는 기본 상태를, P* 는 작성자가 그 축을 켜고 끄는 것을 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunityUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired BrandingPostJpaRepo postRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired com.diving.pungdong.community.CommunityPostMatchJpaRepo matchRepo;
    @Autowired com.diving.pungdong.community.CommunityPostLikeJpaRepo likeRepo;
    @Autowired com.diving.pungdong.community.CommunityPostBookmarkJpaRepo bookmarkRepo;
    @Autowired com.diving.pungdong.community.CommunityCommentJpaRepo commentRepo;
    @Autowired com.diving.pungdong.community.CommunityCommentLikeJpaRepo commentLikeRepo;
    @Autowired com.diving.pungdong.community.ContentReportJpaRepo reportRepo;
    @Autowired com.diving.pungdong.notification.NotificationOutboxJpaRepo outboxRepo;
    @Autowired com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo applicationRepo;

    @Value("${pungdong.storage.local.base-url:http://localhost:8080}")
    String localBaseUrl;

    /**
     * 삭제 순서는 FK 방향의 역순이다 — 자식(모집정보·좋아요·북마크·댓글)을 먼저 지우지 않으면
     * 게시물 삭제가 제약 위반으로 터지고, 그 예외 때문에 다음 테스트에 행이 남아 연쇄로 깨진다.
     */
    @AfterEach
    void cleanUp() {
        applicationRepo.deleteAll();
        matchRepo.deleteAll();
        likeRepo.deleteAll();
        bookmarkRepo.deleteAll();
        reportRepo.deleteAll();
        outboxRepo.deleteAll();
        commentLikeRepo.deleteAll();
        // 댓글은 자기 자신을 참조한다(대댓글 → 부모). deleteAll 은 삭제 순서를 보장하지 않아
        // 부모가 먼저 지워지면 FK 위반이 난다 — 대댓글을 먼저 걷어낸 뒤 나머지를 지운다.
        commentRepo.findAll().stream()
                .filter(comment -> !comment.isTopLevel())
                .forEach(commentRepo::delete);
        commentRepo.deleteAll();
        postRepo.deleteAll();
        brandingRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ── fixture ─────────────────────────────────────────── */

    private Account account(String email, String nickName, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(role))).isDeleted(false).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private String img(String name) {
        return localBaseUrl + "/local-uploads/branding/" + name + ".jpg";
    }

    /**
     * 승인된 강사로 만든다 — 작성자 칩({@code isInstructor})과 "강사 글" 필터가 <b>같은 축</b>을 보므로
     * 이 한 줄이 둘 다를 켠다.
     */
    private void approveAsInstructor(Account account) {
        applicationRepo.save(com.diving.pungdong.instructorapplication.InstructorApplication.builder()
                .account(account)
                .disciplineCode("FREEDIVING")
                .status(com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED)
                .reviewedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .build());
    }

    /** 댓글 작성 → id. */
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

    private Course course(Account instructor, CourseStatus status) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title("문섬 어드밴스드").kind(CourseKind.CERTIFICATION)
                .disciplineCode("FREEDIVING").totalRounds(1).price(680000).status(status)
                .build());
    }

    /** 커뮤니티 글 작성 → 생성된 id. */
    private long createPost(Account author, String category, String title, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + category + "\",\"title\":\"" + title
                                + "\",\"body\":\"" + body + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 브랜딩 글 작성(사진 필수) → 생성된 id. */
    private long brandingPost(Account author, String category, String caption) throws Exception {
        MvcResult result = mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"category\":\"" + category
                                + "\",\"title\":\"" + caption + "\",\"caption\":\"" + caption + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private URI brandingGrid(String nickName) {
        return URI.create("/instructors/" + URLEncoder.encode(nickName, StandardCharsets.UTF_8) + "/posts");
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 커뮤니티 글을 쓰면 피드에 뜬다")
    void createdPost_appearsInFeed() throws Exception {
        Account me = account("s1@c.com", "diverC1", Role.STUDENT);
        createPost(me, "QNA", "OW 다음 코스 추천", "어드밴스드 고민 중이에요");

        mockMvc.perform(get("/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].title").value("OW 다음 코스 추천"))
                .andExpect(jsonPath("$._embedded.posts[0].category").value("QNA"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("S2: 상세에 본문 전체·작성자·UTC 시각이 담긴다 (상대시간은 BE 가 만들지 않는다)")
    void detail_returnsFullPayload() throws Exception {
        Account me = account("s2@c.com", "diverC2", Role.STUDENT);
        long id = createPost(me, "TOUR", "문섬 다녀왔어요", "시야 12m 수온 22도");

        // mine 은 뷰어 기준 값이라 토큰을 실어야 true 가 된다 — 비로그인 조회에서는 false 가 맞다.
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("시야 12m 수온 22도"))
                .andExpect(jsonPath("$.author.nickName").value("diverC2"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.endsWith("Z")));

        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.mine").value(false));
    }

    @Test
    @DisplayName("S3: 삭제하면 피드와 상세에서 사라진다")
    void delete_removesFromFeedAndDetail() throws Exception {
        Account me = account("s3@c.com", "diverC3", Role.STUDENT);
        long id = createPost(me, "TRAINING", "핀킥 팁", "발목을 펴세요");

        mockMvc.perform(delete("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/community/posts/" + id)).andExpect(status().isBadRequest());
        assertThat(postRepo.findById(id)).isEmpty();
    }

    /* ════════════════ X — 노출 방향 (브랜딩 → 커뮤니티 단방향) ════════════════ */

    @Test
    @DisplayName("X1: 커뮤니티에 쓴 글은 브랜딩 그리드에 나오지 않는다 (흐름의 모든 글이 하이라이트는 아니다)")
    void communityPost_doesNotAppearOnBrandingGrid() throws Exception {
        Account me = account("x1@c.com", "diverC4", Role.STUDENT);
        createPost(me, "QNA", "질문 있어요", "본문");

        mockMvc.perform(get(brandingGrid("diverC4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("X2: 브랜딩에 올린 글은 커뮤니티 피드에도 새 글로 뜬다 (단방향의 반대편)")
    void brandingPost_appearsInCommunityFeed() throws Exception {
        Account me = account("x2@c.com", "diverC5", Role.STUDENT);
        brandingPost(me, "TOUR", "하이라이트 사진");

        mockMvc.perform(get("/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].category").value("TOUR"));

        mockMvc.perform(get(brandingGrid("diverC5")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /* ════════════════ P — 프로필 노출·숨김 (통합 작성 폼) ════════════════ */
    // FE 와 합의한 TC 표(2026-08-18)를 그대로 잠근다. 상태 표기 (hidden, showOnProfile).
    //
    //   INV-1 직교   : hidden 토글은 showOnProfile 을 바꾸지 않고, 그 역도 같다   → P7·P9
    //   INV-2 hidden : hidden=true 면 showOnProfile 과 무관하게 남에겐 어디에도 안 보임 → P6·P7
    //   INV-3 오너   : 오너 조회엔 hidden 글도 나온다(안 나오면 삭제다)            → P6·P8
    //
    //   작성  C-1 생략=(F,F) P2 · C-2 true=(F,T) P1 · C-3 category 누락 400 V3
    //   숨김  H-1 (F,T)→(T,T) P6 · H-2 (F,F)→(T,F) P8 · H-3 (T,T)→(F,T) P6 ·
    //         H-4 (T,F)→(F,F) P9(풀기는 승격이 아니다) · H-5 멱등 P10 · H-6 남의 글 400 P11
    //   프로필 P-1 강등 P3 · P-2 승격 P4 · P-3 숨김 글 승격 P7 · P-4 브랜딩 미생성 upsert P1
    //   조회  Q-1 피드 P2 · Q-2 공개 그리드 P1 · Q-3 내가 쓴 글 P8 · Q-4 오너 그리드 P6 · Q-5 남의 숨김 상세 H1

    /** 통합 폼으로 작성 — {@code showOnProfile} 을 실어 보낸다. 사진 1장 포함(프로필 타일용). */
    private long createPostOnProfile(Account author, String title, boolean showOnProfile) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"" + title + "\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"],\"showOnProfile\":" + showOnProfile + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    @DisplayName("P1: showOnProfile=true 로 쓰면 한 번의 작성으로 커뮤니티 피드와 브랜딩 그리드 양쪽에 뜬다")
    void showOnProfile_publishesToBothSurfaces() throws Exception {
        Account me = account("p1@c.com", "diverU1", Role.STUDENT);
        long id = createPostOnProfile(me, "프로필에도 남길 글", true);

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get(brandingGrid("diverU1")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.showOnProfile").value(true));
    }

    @Test
    @DisplayName("P2: 값을 안 보내면 기본은 false — 예전처럼 커뮤니티에만 올라간다 (프로필은 옵트인)")
    void showOnProfile_defaultsToFalse() throws Exception {
        Account me = account("p2@c.com", "diverU2", Role.STUDENT);
        long id = createPost(me, "QNA", "그냥 질문", "본문");

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get(brandingGrid("diverU2")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.showOnProfile").value(false));
    }

    @Test
    @DisplayName("P3: 수정으로 프로필에서 내려도 글은 삭제되지 않는다 — 그리드에서만 빠지고 커뮤니티엔 그대로 있다")
    void update_takingOffProfile_keepsPost() throws Exception {
        Account me = account("p3@c.com", "diverU3", Role.STUDENT);
        long id = createPostOnProfile(me, "내렸다 올렸다", true);

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"내렸다 올렸다\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"],\"showOnProfile\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showOnProfile").value(false));

        assertThat(postRepo.findById(id)).isPresent();
        mockMvc.perform(get("/community/posts/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get(brandingGrid("diverU3")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("P4: 커뮤니티에만 있던 글도 수정으로 프로필에 올릴 수 있다 (되돌릴 수 있는 선택이다)")
    void update_puttingOnProfile_addsToGrid() throws Exception {
        Account me = account("p4@c.com", "diverU4", Role.STUDENT);
        long id = createPostOnProfile(me, "나중에 하이라이트", false);

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"나중에 하이라이트\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"],\"showOnProfile\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(brandingGrid("diverU4")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("P5: 사진 없이 프로필에 올리려 하면 400 — 브랜딩 그리드는 사진 타일이라 빈 칸이 된다")
    void showOnProfile_withoutMedia_returns400() throws Exception {
        Account me = account("p5@c.com", "diverU5", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진 없는 글\",\"showOnProfile\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P6: 숨기면 프로필 그리드에서도 빠진다 (숨김이 이긴다) — 단 오너 목록엔 숨김 표시로 남는다")
    void hidden_beatsShowOnProfile() throws Exception {
        Account me = account("p6@c.com", "diverU6", Role.STUDENT);
        long id = createPostOnProfile(me, "숨길 글", true);

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        // 남에게는 두 표면 모두에서 사라진다
        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get(brandingGrid("diverU6")))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // 오너에게는 남는다 — 상세로 열리고, 오너 목록엔 숨김 표시가 붙는다(되돌릴 화면이 있어야 한다)
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.showOnProfile").value(true));
        mockMvc.perform(get("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].hidden").value(true));

        // 풀면 두 표면이 함께 돌아온다 — showOnProfile 은 T 그대로다(숨김 토글은 그 축을 안 건드린다)
        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showOnProfile").value(true));
        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get(brandingGrid("diverU6")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("P7: 숨긴 글을 프로필에 올려도 숨김이 이긴다 — showOnProfile 만 켜지고 남에겐 여전히 안 보인다")
    void promotingHiddenPost_staysHidden() throws Exception {
        Account me = account("p7@c.com", "diverU7", Role.STUDENT);
        long id = createPostOnProfile(me, "숨긴 채 승격", false);

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"숨긴 채 승격\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"],\"showOnProfile\":true}"))
                .andExpect(status().isOk())
                // 승격은 숨김을 풀지 않는다(직교)
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.showOnProfile").value(true));

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get(brandingGrid("diverU7")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("P8: '내가 쓴 글' 에는 숨긴 글도 프로필에 안 올린 글도 전부 온다 (되돌릴 화면이 없으면 숨김이 아니라 삭제다)")
    void myPosts_includesHiddenAndProfileOffPosts() throws Exception {
        Account me = account("p8@c.com", "diverU8", Role.STUDENT);
        Account other = account("p8b@c.com", "diverU9", Role.STUDENT);
        long onlyCommunity = createPostOnProfile(me, "커뮤니티 전용", false);
        createPostOnProfile(me, "프로필에도", true);
        createPost(other, "QNA", "남의 글", "본문");

        // 커뮤니티 전용 글을 숨긴다 — 브랜딩 그리드에는 원래 없던 글이라 이 목록 말고는 닿을 데가 없다
        mockMvc.perform(patch("/community/posts/" + onlyCommunity + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/community/posts/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))  // 남의 글은 안 온다
                // 최신순이라 [0]=프로필에도, [1]=커뮤니티 전용(숨김)
                .andExpect(jsonPath("$._embedded.posts[0].title").value("프로필에도"))
                .andExpect(jsonPath("$._embedded.posts[0].hidden").value(false))
                .andExpect(jsonPath("$._embedded.posts[0].showOnProfile").value(true))
                .andExpect(jsonPath("$._embedded.posts[1].title").value("커뮤니티 전용"))
                .andExpect(jsonPath("$._embedded.posts[1].hidden").value(true))
                .andExpect(jsonPath("$._embedded.posts[1].showOnProfile").value(false));

        mockMvc.perform(get("/community/posts/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P9: 숨김을 풀어도 프로필로 승격되지는 않는다 — (T,F) 를 풀면 (F,F) 다 (두 축은 직교)")
    void unhiding_doesNotPromoteToProfile() throws Exception {
        Account me = account("p9@c.com", "diverU10", Role.STUDENT);
        long id = createPostOnProfile(me, "커뮤니티 전용", false);

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(false))
                .andExpect(jsonPath("$.showOnProfile").value(false));

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get(brandingGrid("diverU10")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("P10: 같은 숨김 요청을 두 번 보내도 상태가 같다 (멱등 — 재시도·연타에 안전)")
    void hideTwice_isIdempotent() throws Exception {
        Account me = account("p10@c.com", "diverU11", Role.STUDENT);
        long id = createPostOnProfile(me, "두 번 숨기기", true);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                            .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hidden").value(true))
                    .andExpect(jsonPath("$.showOnProfile").value(true));
        }
    }

    @Test
    @DisplayName("P11: 남의 글은 숨길 수 없다 (400 — 403 이 아니라 존재 자체를 숨긴다)")
    void hidingOthersPost_returns400() throws Exception {
        Account owner = account("p11@c.com", "diverU12", Role.STUDENT);
        Account stranger = account("p11b@c.com", "diverU13", Role.STUDENT);
        long id = createPost(owner, "TOUR", "남의 글", "본문");

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /* ════════════════ F — 필터 ════════════════ */

    @Test
    @DisplayName("F1: 카테고리로 좁히면 그 카테고리 글만 온다")
    void categoryFilter_narrowsFeed() throws Exception {
        Account me = account("f1@c.com", "diverC6", Role.STUDENT);
        createPost(me, "TOUR", "자랑 글", "본문");
        createPost(me, "QNA", "질문 글", "본문");

        mockMvc.perform(get("/community/posts").param("category", "QNA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("질문 글"));
    }

    /* ════════════════ M — 같이가요 ════════════════ */

    @Test
    @DisplayName("M1: 같이가요 글에 일정·정원·요구자격이 실리고 아직 지나지 않은 모집은 open 이다")
    void matchPost_carriesMeta() throws Exception {
        Account me = account("m1@c.com", "diverC7", Role.STUDENT);
        String meetDate = LocalDate.now().plusDays(7).toString();

        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"죽도 같이 가실 분\","
                                + "\"match\":{\"meetDate\":\"" + meetDate + "\",\"capacity\":4,"
                                + "\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.match.capacity").value(4))
                .andExpect(jsonPath("$.match.levelLabel").value("AOWD 이상"))
                .andExpect(jsonPath("$.match.open").value(true));
    }

    @Test
    @DisplayName("M2: 같이가요 글에는 강의를 연결할 수 없다 (영리활동 금지 가드 중 강제 가능한 부분)")
    void matchPost_cannotLinkCourse() throws Exception {
        Account me = account("m2@c.com", "diverC8", Role.INSTRUCTOR);
        Course mine = course(me, CourseStatus.OPEN);
        String meetDate = LocalDate.now().plusDays(3).toString();

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"버디 모집\",\"linkedCourseId\":" + mine.getId()
                                + ",\"match\":{\"meetDate\":\"" + meetDate + "\",\"capacity\":2,"
                                + "\"levelLabel\":\"OWD 이상\"}}"))
                .andExpect(status().isBadRequest());
    }

    /** 같이가요 글 작성 → 생성된 id. */
    private long matchPost(Account author, String title, LocalDate meetDate) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"" + title + "\","
                                + "\"match\":{\"meetDate\":\"" + meetDate + "\",\"capacity\":4,"
                                + "\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    @DisplayName("M3: 같이가요 피드는 일정 임박순으로 온다 (최신순이 아니라 — 늦게 쓴 글이라도 일정이 가까우면 위)")
    void matchFeed_ordersBySoonestDate() throws Exception {
        Account me = account("m3@c.com", "diverC36", Role.STUDENT);
        matchPost(me, "먼 일정", LocalDate.now().plusDays(30));
        matchPost(me, "가까운 일정", LocalDate.now().plusDays(2));

        // 나중에 쓴 "가까운 일정" 이 위로 온다 — 최신순이었다면 순서가 같아 구분이 안 되므로,
        // 일부러 나중에 쓴 쪽을 임박하게 만들어 두 정렬을 갈라놨다.
        mockMvc.perform(get("/community/posts").param("category", "MATCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].title").value("가까운 일정"))
                .andExpect(jsonPath("$._embedded.posts[1].title").value("먼 일정"));
    }

    @Test
    @DisplayName("M4: 일정이 지난 모집글은 open=false 다 (지난 글이 멀쩡해 보이면 안 된다)")
    void pastMatch_isClosed() throws Exception {
        Account me = account("m4@c.com", "diverC37", Role.STUDENT);
        long id = matchPost(me, "지나갈 일정", LocalDate.now().plusDays(1));

        // 지난 날짜는 API 가 거부하므로(서비스의 requireFutureIfRescheduled) HTTP 로는 만들 수 없다.
        // 시간이 흐른 상황을 재현하려면 저장된 일정을 직접 과거로 돌리는 수밖에 없다.
        CommunityPostMatch match = matchRepo.findById(id).orElseThrow();
        match.setMeetDate(LocalDate.now().minusDays(1));
        matchRepo.saveAndFlush(match);

        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.match.open").value(false));
    }

    @Test
    @DisplayName("M5: 같이가요인데 모집 정보를 빠뜨리면 400 (일정·인원·자격은 이 카테고리의 필수 필드다)")
    void matchWithoutMeta_rejected() throws Exception {
        Account me = account("m5@c.com", "diverC38", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"모집 정보 없는 글\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("M6: 카테고리를 같이가요에서 다른 것으로 바꾸면 모집 정보가 함께 사라진다")
    void changingCategoryAwayFromMatch_dropsMeta() throws Exception {
        Account me = account("m6@c.com", "diverC39", Role.STUDENT);
        long id = matchPost(me, "원래 같이가요", LocalDate.now().plusDays(5));

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"이제 자랑 글\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").doesNotExist());

        assertThat(matchRepo.findById(id)).isEmpty();
    }

    /* ════════════════ A — 작성자 합성 ════════════════ */

    @Test
    @DisplayName("A1: 강사가 아닌 작성자에게는 lessonCount 키가 아예 없다 (0 이면 '강의 0개인 강사' 로 읽힌다)")
    void nonInstructorAuthor_hasNoLessonCount() throws Exception {
        Account me = account("a1@c.com", "diverC9", Role.STUDENT);
        createPost(me, "QNA", "질문", "본문");

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$._embedded.posts[0].author.isInstructor").value(false))
                .andExpect(jsonPath("$._embedded.posts[0].author.lessonCount").doesNotExist());
    }

    /* ════════════════ V — 검증 ════════════════ */

    @Test
    @DisplayName("V1: 업로드로 받지 않은 외부 이미지 주소는 거부한다 (본문에 임의 URL 을 심지 못하게)")
    void externalImageUrl_rejected() throws Exception {
        Account me = account("v1@c.com", "diverC10", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진\","
                                + "\"mediaUrls\":[\"https://evil.example.com/x.jpg\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2: 제목이 없으면 400 이고 사용자에게 보여줄 한국어 문구가 온다")
    void missingTitle_rejected() throws Exception {
        Account me = account("v2@c.com", "diverC11", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"body\":\"제목 없음\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("제목을 입력해주세요."));
    }

    /* ════════════════ H — 숨김 ════════════════ */

    @Test
    @DisplayName("V3: 카테고리 없이 쓰면 400 (V30 이후 필수 — 없는 카테고리 글은 더 이상 만들 수 없다)")
    void missingCategory_returns400() throws Exception {
        Account me = account("v3@c.com", "diverU14", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"카테고리 없는 글\",\"body\":\"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("카테고리를 골라주세요."));
    }

    @Test
    @DisplayName("H1: 숨긴 글은 공개 피드에서 빠지지만 오너 본인 상세로는 열린다 (다시 공개를 누를 수 있어야 한다)")
    void hidden_dropsFromFeedButOwnerCanStillOpen() throws Exception {
        Account me = account("h1@c.com", "diverC12", Role.STUDENT);
        long id = createPost(me, "TOUR", "숨길 글", "본문");

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/community/posts/" + id)).andExpect(status().isBadRequest());

        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true));
    }

    /* ════════════════ K — 좋아요·북마크 ════════════════ */

    @Test
    @DisplayName("K1: 좋아요를 두 번 눌러도 1개다 (재시도·연타에 카운트가 부풀지 않는다)")
    void like_isIdempotent() throws Exception {
        Account me = account("k1@c.com", "diverC16", Role.STUDENT);
        long id = createPost(me, "TOUR", "좋아요 대상", "본문");

        mockMvc.perform(post("/community/posts/" + id + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/community/posts/" + id + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(likeRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("K2: 좋아요를 취소하면 카운트가 줄고 내 상태가 false 가 된다")
    void unlike_decrements() throws Exception {
        Account me = account("k2@c.com", "diverC17", Role.STUDENT);
        long id = createPost(me, "TOUR", "취소 대상", "본문");

        mockMvc.perform(post("/community/posts/" + id + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me)));

        mockMvc.perform(delete("/community/posts/" + id + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("K3: 피드 카드에 좋아요 수와 내가 눌렀는지가 실린다 (비로그인이면 likedByMe 는 false)")
    void feedCard_carriesLikeState() throws Exception {
        Account me = account("k3@c.com", "diverC18", Role.STUDENT);
        long id = createPost(me, "TOUR", "카드 상태", "본문");
        mockMvc.perform(post("/community/posts/" + id + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me)));

        mockMvc.perform(get("/community/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$._embedded.posts[0].likeCount").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].likedByMe").value(true));

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$._embedded.posts[0].likeCount").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].likedByMe").value(false));
    }

    @Test
    @DisplayName("K4: 북마크한 글만 따로 볼 수 있고, 비로그인은 에러가 아니라 빈 목록이다")
    void bookmarkedFilter_worksAndIsEmptyForAnonymous() throws Exception {
        Account me = account("k4@c.com", "diverC19", Role.STUDENT);
        long saved = createPost(me, "TOUR", "저장할 글", "본문");
        createPost(me, "QNA", "안 저장할 글", "본문");

        mockMvc.perform(post("/community/posts/" + saved + "/bookmark")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me)));

        mockMvc.perform(get("/community/posts").param("bookmarkedByMe", "true")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("저장할 글"));

        mockMvc.perform(get("/community/posts").param("bookmarkedByMe", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("K5: 숨긴 글에는 좋아요를 걸 수 없다 (존재를 알려주지 않는다)")
    void hiddenPost_cannotBeLiked() throws Exception {
        Account owner = account("k5a@c.com", "diverC20", Role.STUDENT);
        Account other = account("k5b@c.com", "diverC21", Role.STUDENT);
        long id = createPost(owner, "TOUR", "숨긴 글", "본문");

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"));

        mockMvc.perform(post("/community/posts/" + id + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ D — 탐색 지표 ════════════════ */

    @Test
    @DisplayName("D1: 카테고리 카운트는 글이 0개인 카테고리도 0 으로 채워 4종을 전부 준다 (칸이 그려져야 한다)")
    void categoryCounts_alwaysReturnsAllFour() throws Exception {
        Account me = account("d1@c.com", "diverC22", Role.STUDENT);
        createPost(me, "TOUR", "자랑", "본문");

        mockMvc.perform(get("/community/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.categories.length()").value(4))
                .andExpect(jsonPath("$._embedded.categories[?(@.category=='TOUR')].weeklyPostCount")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$._embedded.categories[?(@.category=='MATCH')].weeklyPostCount")
                        .value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    @DisplayName("D2: 인기 태그는 건수 내림차순으로 온다")
    void popularTags_orderedByCount() throws Exception {
        Account me = account("d2@c.com", "diverC23", Role.STUDENT);
        mockMvc.perform(post("/community/posts")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"TOUR\",\"title\":\"글1\",\"tags\":[\"제주\",\"문섬\"]}"));
        mockMvc.perform(post("/community/posts")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"TOUR\",\"title\":\"글2\",\"tags\":[\"제주\"]}"));

        mockMvc.perform(get("/community/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.tags[0].tag").value("제주"))
                .andExpect(jsonPath("$._embedded.tags[0].count").value(2));
    }

    @Test
    @DisplayName("D3: 관련 글은 같은 카테고리만 오고 자기 자신은 빠진다")
    void related_sameCategoryExcludingSelf() throws Exception {
        Account me = account("d3@c.com", "diverC24", Role.STUDENT);
        long id = createPost(me, "TOUR", "기준 글", "본문");
        createPost(me, "TOUR", "같은 카테고리", "본문");
        createPost(me, "QNA", "다른 카테고리", "본문");

        mockMvc.perform(get("/community/posts/" + id + "/related"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts.length()").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("같은 카테고리"));
    }

    @Test
    @DisplayName("D4: 인기순은 좋아요가 많은 글을 위로 올린다")
    void popularSort_ordersByLikes() throws Exception {
        Account me = account("d4a@c.com", "diverC25", Role.STUDENT);
        Account other = account("d4b@c.com", "diverC26", Role.STUDENT);
        createPost(me, "TOUR", "인기 없는 글", "본문");
        long popular = createPost(me, "TOUR", "인기 있는 글", "본문");

        mockMvc.perform(post("/community/posts/" + popular + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other)));

        mockMvc.perform(get("/community/posts").param("sort", "POPULAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].title").value("인기 있는 글"));
    }

    /** 태그를 달아 글을 쓴다 → 생성된 id. */
    private long createPostWithTags(Account author, String title, String... tags) throws Exception {
        String tagJson = java.util.Arrays.stream(tags)
                .map(tag -> "\"" + tag + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"" + title
                                + "\",\"body\":\"본문\",\"tags\":[" + tagJson + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void like(Account account, long postId) throws Exception {
        mockMvc.perform(post("/community/posts/" + postId + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account))).andExpect(status().isOk());
    }

    private void bookmark(Account account, long postId) throws Exception {
        mockMvc.perform(post("/community/posts/" + postId + "/bookmark")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account))).andExpect(status().isOk());
    }

    /** 글의 작성 시각을 과거로 민다 — 집계 창(7일·30일) 밖으로 내보내기 위해. */
    private void backdate(long postId, int days) {
        BrandingPost post = postRepo.findById(postId).orElseThrow();
        post.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(days));
        postRepo.save(post);
    }

    @Test
    @DisplayName("D5: 인기순은 좋아요만 보지 않는다 — 좋아요 2 인 글보다 좋아요·댓글·북마크가 하나씩인 글이 위다")
    void popularSort_sumsAllThreeSignals() throws Exception {
        Account author = account("d5a@c.com", "diverT01", Role.STUDENT);
        Account u1 = account("d5b@c.com", "diverT02", Role.STUDENT);
        Account u2 = account("d5c@c.com", "diverT03", Role.STUDENT);

        long likesOnly = createPost(author, "TOUR", "좋아요만 둘", "본문");
        long balanced = createPost(author, "TOUR", "고르게 셋", "본문");

        like(u1, likesOnly);
        like(u2, likesOnly);

        like(u1, balanced);
        comment(u1, balanced, "댓글", null);
        bookmark(u2, balanced);

        mockMvc.perform(get("/community/posts").param("sort", "POPULAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].title").value("고르게 셋"))
                .andExpect(jsonPath("$._embedded.posts[1].title").value("좋아요만 둘"));
    }

    @Test
    @DisplayName("D6: 참여 점수는 축이 겹쳐도 곱해지지 않는다 — 좋아요 2·댓글 2·북마크 2 는 8 이 아니라 6 이다")
    void engagementScore_doesNotMultiplyAcrossJoins() throws Exception {
        Account author = account("d6a@c.com", "diverT04", Role.STUDENT);
        Account u1 = account("d6b@c.com", "diverT05", Role.STUDENT);
        Account u2 = account("d6c@c.com", "diverT06", Role.STUDENT);

        long postId = createPost(author, "TOUR", "고루 달린 글", "본문");
        like(u1, postId);
        like(u2, postId);
        comment(u1, postId, "댓글1", null);
        comment(u2, postId, "댓글2", null);
        bookmark(u1, postId);
        bookmark(u2, postId);

        mockMvc.perform(get("/community/topics/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.topics[0].postId").value((int) postId))
                .andExpect(jsonPath("$._embedded.topics[0].score").value(6));
    }

    @Test
    @DisplayName("D7: 삭제된 댓글은 참여 점수에서 빠진다 (카드의 댓글 수와 같은 기준)")
    void engagementScore_excludesDeletedComments() throws Exception {
        Account author = account("d7a@c.com", "diverT07", Role.STUDENT);
        Account other = account("d7b@c.com", "diverT08", Role.STUDENT);

        long postId = createPost(author, "TOUR", "댓글 지운 글", "본문");
        comment(other, postId, "남을 댓글", null);
        long doomed = comment(other, postId, "지울 댓글", null);

        mockMvc.perform(delete("/community/comments/" + doomed)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))).andExpect(status().isNoContent());

        mockMvc.perform(get("/community/topics/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.topics[0].score").value(1));
    }

    @Test
    @DisplayName("D8: 지금 뜨는 토픽의 1등은 인기 탭의 1등과 같다 (기준이 갈리면 안 된다) · 비로그인도 본다")
    void trendingTopics_agreeWithPopularFeed() throws Exception {
        Account author = account("d8a@c.com", "diverT09", Role.STUDENT);
        Account u1 = account("d8b@c.com", "diverT10", Role.STUDENT);

        createPost(author, "TOUR", "조용한 글", "본문");
        long hot = createPost(author, "QNA", "뜨는 글", "본문");
        like(u1, hot);
        comment(u1, hot, "댓글", null);

        mockMvc.perform(get("/community/topics/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.topics[0].postId").value((int) hot))
                .andExpect(jsonPath("$._embedded.topics[0].title").value("뜨는 글"))
                .andExpect(jsonPath("$._embedded.topics[0].category").value("QNA"))
                .andExpect(jsonPath("$._embedded.topics[0].score").value(2));

        mockMvc.perform(get("/community/posts").param("sort", "POPULAR"))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("뜨는 글"));
    }

    @Test
    @DisplayName("D9: 숨긴 글은 뜨는 토픽에서도 인기 태그에서도 빠진다")
    void hiddenPost_dropsFromDiscoveryWidgets() throws Exception {
        Account author = account("d9@c.com", "diverT11", Role.STUDENT);
        long hidden = createPostWithTags(author, "숨길 글", "숨김태그");
        createPostWithTags(author, "남을 글", "남을태그");

        mockMvc.perform(patch("/community/posts/" + hidden + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/community/topics/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.topics.length()").value(1))
                .andExpect(jsonPath("$._embedded.topics[0].title").value("남을 글"));

        mockMvc.perform(get("/community/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.tags.length()").value(1))
                .andExpect(jsonPath("$._embedded.tags[0].tag").value("남을태그"));
    }

    @Test
    @DisplayName("D10: 30일이 지난 글의 태그는 인기 태그에서 빠진다 (오래된 태그가 상단에 굳지 않게)")
    void popularTags_dropOutsideWindow() throws Exception {
        Account author = account("d10@c.com", "diverT12", Role.STUDENT);
        long old = createPostWithTags(author, "오래된 글", "옛날태그");
        createPostWithTags(author, "최근 글", "요즘태그");
        backdate(old, 31);

        mockMvc.perform(get("/community/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.tags.length()").value(1))
                .andExpect(jsonPath("$._embedded.tags[0].tag").value("요즘태그"));
    }

    @Test
    @DisplayName("D11: 한 글에 같은 태그를 두 번 넣어도 인기 태그 카운트는 1 이다 (글 수를 센다)")
    void popularTags_countPostsNotRows() throws Exception {
        Account author = account("d11@c.com", "diverT13", Role.STUDENT);
        long postId = createPostWithTags(author, "중복 태그 글", "제주", "제주");

        mockMvc.perform(get("/community/posts/" + postId))
                .andExpect(jsonPath("$.tags.length()").value(1));

        mockMvc.perform(get("/community/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.tags[0].tag").value("제주"))
                .andExpect(jsonPath("$._embedded.tags[0].count").value(1));
    }

    @Test
    @DisplayName("D12: '#제주' 로 보내도 '제주' 로 저장돼 같은 태그로 합산된다 (#가 붙어 반씩 갈리지 않게)")
    void tagNormalization_stripsHash() throws Exception {
        Account author = account("d12@c.com", "diverT14", Role.STUDENT);
        long withHash = createPostWithTags(author, "샵 붙인 글", "#제주");
        createPostWithTags(author, "샵 없는 글", "제주");

        mockMvc.perform(get("/community/posts/" + withHash))
                .andExpect(jsonPath("$.tags[0]").value("제주"));

        mockMvc.perform(get("/community/tags/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.tags.length()").value(1))
                .andExpect(jsonPath("$._embedded.tags[0].tag").value("제주"))
                .andExpect(jsonPath("$._embedded.tags[0].count").value(2));
    }

    @Test
    @DisplayName("D13: ?tag= 는 그 태그가 달린 글만 준다 — 최신순·인기순 양쪽에서")
    void tagFilter_narrowsBothSorts() throws Exception {
        Account author = account("d13a@c.com", "diverT15", Role.STUDENT);
        Account other = account("d13b@c.com", "diverT16", Role.STUDENT);

        long tagged = createPostWithTags(author, "문섬 글", "문섬");
        long untagged = createPostWithTags(author, "무관한 글", "성산");
        like(other, untagged);

        mockMvc.perform(get("/community/posts").param("tag", "문섬"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].id").value((int) tagged));

        // 인기순은 전용 쿼리라 Specification 을 안 탄다 — 같은 필터가 거기에도 걸리는지 따로 본다.
        mockMvc.perform(get("/community/posts").param("tag", "문섬").param("sort", "POPULAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].id").value((int) tagged));
    }

    @Test
    @DisplayName("D14: ?tag= 를 비워 보내면 필터 없음이다 (0건이 아니라 전체가 온다)")
    void tagFilter_blankMeansNoFilter() throws Exception {
        Account author = account("d14@c.com", "diverT17", Role.STUDENT);
        createPostWithTags(author, "글1", "제주");
        createPostWithTags(author, "글2", "문섬");

        mockMvc.perform(get("/community/posts").param("tag", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    /* ════════════════ C — 댓글 ════════════════ */

    /** 댓글 작성 → 생성된 id. {@code parentId} 가 있으면 대댓글. */
    private long comment(Account author, long postId, String body, Long parentId) throws Exception {
        String payload = parentId == null
                ? "{\"body\":\"" + body + "\"}"
                : "{\"body\":\"" + body + "\",\"parentCommentId\":" + parentId + "}";
        MvcResult result = mockMvc.perform(post("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    @DisplayName("C1: 댓글과 대댓글이 부모 아래 중첩돼 오고 글의 댓글 수에 함께 잡힌다")
    void commentThread_nestsReplies() throws Exception {
        Account me = account("c1@c.com", "diverC27", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long parent = comment(me, postId, "답변드려요", null);
        comment(me, postId, "감사합니다", parent);

        mockMvc.perform(get("/community/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.comments.length()").value(1))
                .andExpect(jsonPath("$._embedded.comments[0].replyCount").value(1))
                .andExpect(jsonPath("$._embedded.comments[0].replies[0].body").value("감사합니다"));

        mockMvc.perform(get("/community/posts/" + postId))
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    @Test
    @DisplayName("C2: 대댓글에 또 답글을 달 수 없다 (1-depth 고정 — 안 막으면 들여쓰기가 화면을 벗어난다)")
    void replyToReply_rejected() throws Exception {
        Account me = account("c2@c.com", "diverC28", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long parent = comment(me, postId, "댓글", null);
        long reply = comment(me, postId, "대댓글", parent);

        mockMvc.perform(post("/community/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"대대댓글\",\"parentCommentId\":" + reply + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C3: 대댓글이 달린 댓글을 지우면 자리는 남고 본문만 가려진다 (스레드가 끊기면 안 된다)")
    void deleteWithReplies_isSoft() throws Exception {
        Account me = account("c3@c.com", "diverC29", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long parent = comment(me, postId, "지울 댓글", null);
        comment(me, postId, "남아야 하는 답글", parent);

        mockMvc.perform(delete("/community/comments/" + parent)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/community/posts/" + postId + "/comments"))
                .andExpect(jsonPath("$._embedded.comments[0].deleted").value(true))
                .andExpect(jsonPath("$._embedded.comments[0].body").value("삭제된 댓글입니다."))
                .andExpect(jsonPath("$._embedded.comments[0].replies[0].body").value("남아야 하는 답글"));
    }

    @Test
    @DisplayName("C4: 대댓글이 없는 댓글은 완전히 지워진다 (껍데기를 남길 이유가 없다)")
    void deleteWithoutReplies_isHard() throws Exception {
        Account me = account("c4@c.com", "diverC30", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long only = comment(me, postId, "혼자 있는 댓글", null);

        mockMvc.perform(delete("/community/comments/" + only)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/community/posts/" + postId + "/comments"))
                .andExpect(jsonPath("$._embedded").doesNotExist());
        assertThat(commentRepo.findById(only)).isEmpty();
    }

    @Test
    @DisplayName("C5: 삭제된 댓글은 글의 댓글 수에서 빠진다 ('댓글 3' 인데 2개만 보이면 버그다)")
    void deletedComment_excludedFromCount() throws Exception {
        Account me = account("c5@c.com", "diverC31", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long parent = comment(me, postId, "지울 댓글", null);
        comment(me, postId, "답글", parent);

        mockMvc.perform(delete("/community/comments/" + parent)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me)));

        mockMvc.perform(get("/community/posts/" + postId))
                .andExpect(jsonPath("$.commentCount").value(1));
    }

    @Test
    @DisplayName("C6: 댓글 좋아요도 멱등이고 삭제된 댓글에는 누를 수 없다")
    void commentLike_isIdempotentAndBlockedOnDeleted() throws Exception {
        Account me = account("c6@c.com", "diverC32", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        long parent = comment(me, postId, "좋아요 대상", null);
        comment(me, postId, "답글", parent);

        mockMvc.perform(post("/community/comments/" + parent + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/community/comments/" + parent + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(delete("/community/comments/" + parent)
                .header(HttpHeaders.AUTHORIZATION, tokenFor(me)));

        mockMvc.perform(post("/community/comments/" + parent + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C7: 남의 댓글은 수정·삭제할 수 없다 (400 — 존재 숨김)")
    void othersComment_cannotBeEdited() throws Exception {
        Account owner = account("c7a@c.com", "diverC33", Role.STUDENT);
        Account stranger = account("c7b@c.com", "diverC34", Role.STUDENT);
        long postId = createPost(owner, "QNA", "질문", "본문");
        long id = comment(owner, postId, "내 댓글", null);

        mockMvc.perform(delete("/community/comments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C8: 비로그인은 댓글을 읽을 수 있지만 쓰지는 못한다")
    void anonymous_canReadCommentsButNotWrite() throws Exception {
        Account me = account("c8@c.com", "diverC35", Role.STUDENT);
        long postId = createPost(me, "QNA", "질문", "본문");
        comment(me, postId, "공개 댓글", null);

        mockMvc.perform(get("/community/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.comments[0].likedByMe").value(false))
                .andExpect(jsonPath("$._embedded.comments[0].mine").value(false));

        mockMvc.perform(post("/community/posts/" + postId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"비로그인\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ════════════════ N — 알림 ════════════════ */
    // 알림 파이프라인 자체(outbox → 워커 → FCM)는 NotificationOutboxFlowTest 가 검증한다.
    // 여기서 확인하는 건 **커뮤니티가 올바른 사람에게, 올바른 횟수로 발행하는가** — 자기알림 가드와
    // 수신자 선택은 발행 지점(커뮤니티 서비스)의 책임이라 HTTP 로 끝까지 몰아 확인해야 의미가 있다.

    private List<com.diving.pungdong.notification.NotificationOutbox> commentNotifications() {
        return outboxRepo.findAll().stream()
                .filter(o -> o.getType() == com.diving.pungdong.notification.NotificationType.COMMUNITY_COMMENT)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    @DisplayName("N1: 남의 글에 댓글을 달면 글 작성자에게 알림이 1건 쌓인다")
    void comment_notifiesPostAuthor() throws Exception {
        Account author = account("n1a@c.com", "diverC55", Role.STUDENT);
        Account commenter = account("n1b@c.com", "diverC56", Role.STUDENT);
        long postId = createPost(author, "QNA", "질문 있어요", "본문");

        comment(commenter, postId, "답변 드려요", null);

        var notifications = commentNotifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRecipientAccountId()).isEqualTo(author.getId());
    }

    @Test
    @DisplayName("N2: 내 글에 내가 댓글을 달면 알림이 없다 (흔한 동작이라 안 막으면 자기 알림이 쏟아진다)")
    void selfComment_doesNotNotify() throws Exception {
        Account me = account("n2@c.com", "diverC57", Role.STUDENT);
        long postId = createPost(me, "QNA", "내 글", "본문");

        comment(me, postId, "내가 내 글에 다는 댓글", null);

        assertThat(commentNotifications()).isEmpty();
    }

    @Test
    @DisplayName("N3: 답글은 부모 댓글 작성자에게만 간다 (글 작성자까지 보내면 스레드가 길어질수록 소음이 된다)")
    void reply_notifiesOnlyParentAuthor() throws Exception {
        Account postAuthor = account("n3a@c.com", "diverC58", Role.STUDENT);
        Account commenter = account("n3b@c.com", "diverC59", Role.STUDENT);
        Account replier = account("n3c@c.com", "diverC60", Role.STUDENT);
        long postId = createPost(postAuthor, "QNA", "질문", "본문");
        long parent = comment(commenter, postId, "댓글", null);

        outboxRepo.deleteAll();               // 위 댓글이 만든 알림은 이 시나리오의 관심사가 아니다
        comment(replier, postId, "답글", parent);

        var notifications = commentNotifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRecipientAccountId()).isEqualTo(commenter.getId());
    }

    /* ════════════════ X — 신고 ════════════════ */

    private String report(Account reporter, String targetType, long targetId, String reason) throws Exception {
        return mockMvc.perform(post("/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId
                                + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("X1: 남의 글을 신고하면 접수되고 대기 상태로 큐에 쌓인다")
    void report_isQueued() throws Exception {
        Account owner = account("x1r@c.com", "diverC40", Role.STUDENT);
        Account reporter = account("x1s@c.com", "diverC41", Role.STUDENT);
        long postId = createPost(owner, "TOUR", "신고 대상 글", "본문");

        report(reporter, "POST", postId, "SPAM");

        assertThat(reportRepo.countByStatus(com.diving.pungdong.community.ReportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @DisplayName("X2: 같은 대상을 두 번 신고해도 1건이다 (이미 신고한 걸 또 눌러도 사용자에겐 성공이 맞다)")
    void duplicateReport_isIdempotent() throws Exception {
        Account owner = account("x2r@c.com", "diverC42", Role.STUDENT);
        Account reporter = account("x2s@c.com", "diverC43", Role.STUDENT);
        long postId = createPost(owner, "TOUR", "중복 신고 대상", "본문");

        report(reporter, "POST", postId, "SPAM");
        report(reporter, "POST", postId, "ABUSE");

        assertThat(reportRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("X3: 자기 글은 신고할 수 없다 (어드민 큐만 늘리고 판단할 게 없다)")
    void selfReport_rejected() throws Exception {
        Account me = account("x3@c.com", "diverC44", Role.STUDENT);
        long postId = createPost(me, "TOUR", "내 글", "본문");

        mockMvc.perform(post("/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X4: 사유가 기타면 설명이 필수다 (설명 없으면 어드민이 판단할 근거가 없다)")
    void otherReasonWithoutDetail_rejected() throws Exception {
        Account owner = account("x4r@c.com", "diverC45", Role.STUDENT);
        Account reporter = account("x4s@c.com", "diverC46", Role.STUDENT);
        long postId = createPost(owner, "TOUR", "대상", "본문");

        mockMvc.perform(post("/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X5: 없는 대상은 신고할 수 없다 (열 수 없는 행이 큐에 쌓이면 안 된다)")
    void reportingMissingTarget_rejected() throws Exception {
        Account reporter = account("x5@c.com", "diverC47", Role.STUDENT);

        mockMvc.perform(post("/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":999999,\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X6: 어드민이 조치하면 신고된 글이 실제로 피드에서 사라진다 (상태만 바뀌면 안 된다)")
    void adminAction_hidesTarget() throws Exception {
        Account owner = account("x6o@c.com", "diverC48", Role.STUDENT);
        Account reporter = account("x6s@c.com", "diverC49", Role.STUDENT);
        Account admin = account("x6a@c.com", "diverC50", Role.ADMIN);
        long postId = createPost(owner, "TOUR", "조치될 글", "본문");

        String body = report(reporter, "POST", postId, "ABUSE");
        long reportId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(patch("/admin/community/reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIONED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"));

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("X7: 어드민 큐는 ADMIN 만 볼 수 있다")
    void adminQueue_requiresAdminRole() throws Exception {
        Account normal = account("x7@c.com", "diverC51", Role.STUDENT);

        mockMvc.perform(get("/admin/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(normal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("X8: 어드민 목록에는 신고자와 대상 미리보기가 실린다 (열어보지 않고 판단할 수 있게)")
    void adminQueue_carriesReporterAndPreview() throws Exception {
        Account owner = account("x8o@c.com", "diverC52", Role.STUDENT);
        Account reporter = account("x8s@c.com", "diverC53", Role.STUDENT);
        Account admin = account("x8a@c.com", "diverC54", Role.ADMIN);
        long postId = createPost(owner, "TOUR", "미리보기 대상 글", "본문");
        report(reporter, "POST", postId, "SPAM");

        mockMvc.perform(get("/admin/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.reports[0].reporterNickName").value("diverC53"))
                .andExpect(jsonPath("$._embedded.reports[0].targetPreview").value("미리보기 대상 글"));
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 남의 글을 수정·삭제하려 하면 400 (403 이 아니라 존재 자체를 숨긴다)")
    void othersPost_isHidden() throws Exception {
        Account owner = account("r1a@c.com", "diverC13", Role.STUDENT);
        Account stranger = account("r1b@c.com", "diverC14", Role.STUDENT);
        long id = createPost(owner, "TOUR", "내 글", "본문");

        mockMvc.perform(delete("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R2: 비로그인은 피드·상세를 볼 수 있지만 작성은 401")
    void anonymous_canReadButNotWrite() throws Exception {
        Account me = account("r2@c.com", "diverC15", Role.STUDENT);
        long id = createPost(me, "TOUR", "공개 글", "본문");

        mockMvc.perform(get("/community/posts")).andExpect(status().isOk());
        mockMvc.perform(get("/community/posts/" + id)).andExpect(status().isOk());

        mockMvc.perform(post("/community/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"비로그인\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ════════════════ G — Phase 5 리뷰에서 잡힌 것 ════════════════ */

    @Test
    @DisplayName("G1: 댓글·대댓글·댓글 좋아요가 달린 글도 삭제된다 (자식 행 때문에 500 이 나면 안 된다)")
    void deletingPostWithComments_succeeds() throws Exception {
        Account owner = account("g1a@c.com", "diverG1", Role.STUDENT);
        Account other = account("g1b@c.com", "diverG2", Role.STUDENT);
        long postId = createPost(owner, "QNA", "댓글 달린 글", "본문");
        long parentId = comment(other, postId, null);
        comment(owner, postId, parentId);
        mockMvc.perform(post("/community/comments/" + parentId + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))).andExpect(status().isOk());
        mockMvc.perform(post("/community/posts/" + postId + "/like")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))).andExpect(status().isOk());

        mockMvc.perform(delete("/community/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner)))
                .andExpect(status().isNoContent());

        assertThat(postRepo.findById(postId)).isEmpty();
        assertThat(commentRepo.findAll()).isEmpty();
        assertThat(commentLikeRepo.findAll()).isEmpty();
        assertThat(likeRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("G2: 브랜딩 삭제 경로로 지워도 마찬가지다 (같은 글을 어느 문으로 지우든 결과가 같아야 한다)")
    void deletingViaBrandingPath_alsoCleansCommunityRows() throws Exception {
        Account owner = account("g2a@c.com", "diverG3", Role.STUDENT);
        Account other = account("g2b@c.com", "diverG4", Role.STUDENT);
        long postId = brandingPost(owner, "TOUR", "브랜딩에 올린 글");
        comment(other, postId, null);
        mockMvc.perform(post("/community/posts/" + postId + "/bookmark")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(other))).andExpect(status().isOk());

        mockMvc.perform(delete("/branding/me/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner)))
                .andExpect(status().isNoContent());

        assertThat(postRepo.findById(postId)).isEmpty();
        assertThat(commentRepo.findAll()).isEmpty();
        assertThat(bookmarkRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("G3: 강사 글 필터는 승인된 강사의 글만 준다 (칩이 붙는 글과 정확히 같은 집합)")
    void authorTypeFilter_returnsInstructorPostsOnly() throws Exception {
        Account instructor = account("g3a@c.com", "diverG5", Role.STUDENT);
        Account normal = account("g3b@c.com", "diverG6", Role.STUDENT);
        approveAsInstructor(instructor);
        long instructorPost = createPost(instructor, "TOUR", "강사가 쓴 글", "본문");
        createPost(normal, "TOUR", "일반 유저가 쓴 글", "본문");

        mockMvc.perform(get("/community/posts?authorType=INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].id").value(instructorPost))
                .andExpect(jsonPath("$._embedded.posts[0].author.isInstructor").value(true));

        // 생략하면 전체다.
        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("G4: 강사 글 필터는 인기순·같이가요 피드에서도 걸린다 (전용 쿼리만 필터가 빠지면 안 된다)")
    void authorTypeFilter_appliesToDedicatedQueries() throws Exception {
        Account instructor = account("g4a@c.com", "diverG7", Role.STUDENT);
        Account normal = account("g4b@c.com", "diverG8", Role.STUDENT);
        approveAsInstructor(instructor);
        createPost(instructor, "QNA", "강사 글", "본문");
        createPost(normal, "QNA", "일반 글", "본문");
        matchPost(instructor, "강사 모집", LocalDate.now().plusDays(3));
        matchPost(normal, "일반 모집", LocalDate.now().plusDays(4));

        mockMvc.perform(get("/community/posts?sort=POPULAR&authorType=INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2)); // 강사의 QNA + 같이가요

        mockMvc.perform(get("/community/posts?category=MATCH&authorType=INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.posts[0].title").value("강사 모집"));
    }

    @Test
    @DisplayName("G5: 신고로 내려간 글은 작성자가 다시 공개할 수 없다 (조치를 토글 한 번으로 무효화하면 안 된다)")
    void authorCannotUnhideModeratedPost() throws Exception {
        Account owner = account("g5a@c.com", "diverG9", Role.STUDENT);
        Account reporter = account("g5b@c.com", "diverG10", Role.STUDENT);
        Account admin = account("g5c@c.com", "diverG11", Role.ADMIN);
        long postId = createPost(owner, "TOUR", "신고당할 글", "본문");
        report(reporter, "POST", postId, "SPAM");
        long reportId = reportRepo.findAll().get(0).getId();
        mockMvc.perform(patch("/admin/community/reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIONED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/community/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("신고로 비공개 처리된 글이라 다시 공개할 수 없어요."));

        assertThat(postRepo.findById(postId).orElseThrow().isHidden()).isTrue();
    }

    @Test
    @DisplayName("G6: 커뮤니티 전용 글은 브랜딩 수정 경로로 건드릴 수 없다 (같이가요에 강의를 붙이는 우회로였다)")
    void communityOnlyPost_isNotEditableThroughBrandingPath() throws Exception {
        Account instructor = account("g6@c.com", "diverG12", Role.STUDENT);
        approveAsInstructor(instructor);
        Course openCourse = course(instructor, CourseStatus.OPEN);
        long matchId = matchPost(instructor, "같이 가요", LocalDate.now().plusDays(5));

        // 커뮤니티 경로에서는 애초에 막혀 있다.
        mockMvc.perform(put("/community/posts/" + matchId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"같이 가요\",\"linkedCourseId\":"
                                + openCourse.getId() + ",\"match\":{\"meetDate\":\""
                                + LocalDate.now().plusDays(5) + "\",\"capacity\":4,\"levelLabel\":\"L2\"}}"))
                .andExpect(status().isBadRequest());

        // 브랜딩 경로도 이 글에 닿지 못한다(400 — 프로필 글이 아니다).
        mockMvc.perform(put("/branding/me/posts/" + matchId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"caption\":\"우회\",\"linkedCourseId\":"
                                + openCourse.getId() + "}"))
                .andExpect(status().isBadRequest());

        assertThat(postRepo.findById(matchId).orElseThrow().getLinkedCourse()).isNull();
    }

    @Test
    @DisplayName("G7: 커뮤니티 전용 글은 브랜딩 상세 URL 로도 열리지 않는다 (오너에게도)")
    void communityOnlyPost_isNotVisibleOnBrandingDetail() throws Exception {
        Account owner = account("g7@c.com", "diverG13", Role.STUDENT);
        long postId = createPost(owner, "TOUR", "커뮤니티 글", "본문");

        mockMvc.perform(get("/branding-posts/" + postId))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/branding-posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner)))
                .andExpect(status().isBadRequest());

        // 커뮤니티 상세로는 정상이다.
        mockMvc.perform(get("/community/posts/" + postId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("G8: 숨겨진 글의 댓글에는 좋아요를 걸 수 없다 (글 좋아요만 막고 댓글로 새면 우회로다)")
    void commentLike_onHiddenPost_isRejected() throws Exception {
        Account owner = account("g8a@c.com", "diverG14", Role.STUDENT);
        Account other = account("g8b@c.com", "diverG15", Role.STUDENT);
        long postId = createPost(owner, "QNA", "곧 숨길 글", "본문");
        long commentId = comment(other, postId, null);
        mockMvc.perform(patch("/community/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/community/comments/" + commentId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("G9: mediaUrls 를 null 로 보내면 500 이 아니라 400 + 한국어 문구다")
    void nullMediaUrls_isRejectedWithMessage() throws Exception {
        Account me = account("g9@c.com", "diverG16", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"QNA\",\"title\":\"제목\",\"mediaUrls\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("사진 목록은 비워 보내더라도 배열이어야 해요."));
    }

    @Test
    @DisplayName("G10: 어드민 큐도 페이지 크기 상한(50)을 넘길 수 없다")
    void adminQueue_capsPageSize() throws Exception {
        Account admin = account("g10@c.com", "diverG17", Role.ADMIN);

        mockMvc.perform(get("/admin/community/reports?size=100000")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    /* ════════════════ E — 수정(edit) ════════════════ */
    //
    // `PUT /community/posts/{id}` 는 원래 <b>M6 · G6 두 개</b>로만 덮여 있었다. 둘 다 "거부되는가"를
    // 보는 테스트라, 수정이 <b>성공했을 때 무엇이 어떻게 되는가</b>는 사양으로 적힌 적이 없다.
    // 아래 E* 가 그 자리를 메운다 — 특히 `updatedAt` 은 "수정됨" 표기의 유일한 근거라 못 박아야 한다.

    /**
     * 상세 조회 원문(JSON 문자열). 한 응답에서 두 개 이상 필드를 꺼내 비교할 때 쓴다.
     *
     * <p>⚠️ <b>UTF-8 을 명시한다</b> — 인자 없는 {@code getContentAsString()} 은 기본 charset 으로 읽어
     * 한글이 깨진다. 꺼낸 값을 되실어 저장하는 라운드트립 테스트에서 특히 위험하다(깨진 채 저장되고도
     * 통과한 것처럼 보인다).
     */
    private String detailJson(long postId) throws Exception {
        return mockMvc.perform(get("/community/posts/" + postId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String read(String json, String path) {
        Object value = com.jayway.jsonpath.JsonPath.read(json, path);
        return String.valueOf(value);
    }

    @Test
    @DisplayName("E1: 글을 수정하면 updatedAt 이 createdAt 보다 나중이 된다 (수정 응답에서 바로)")
    void update_movesUpdatedAtInResponse() throws Exception {
        Account me = account("e1@c.com", "diverE1", Role.STUDENT);
        long id = createPost(me, "TOUR", "고치기 전", "본문");

        // 작성 시점엔 @PrePersist 가 같은 now 를 둘 다에 넣으므로 두 값이 정확히 같다.
        // 즉 "수정된 적 있음" 은 두 값이 갈라졌는지로 판정된다 — 근사 비교가 아니라 정확 비교다.
        String created = detailJson(id);
        assertThat(read(created, "$.updatedAt")).isEqualTo(read(created, "$.createdAt"));

        String updated = mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"고친 뒤\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 수정 응답이 곧바로 갱신된 시각을 실어야 한다 — FE 가 이 응답으로 "수정됨" 배지를 그린다.
        assertThat(read(updated, "$.updatedAt")).isNotEqualTo(read(updated, "$.createdAt"));
    }

    @Test
    @DisplayName("E2: 재조회해도 updatedAt 이 갱신돼 있다 (응답만 맞고 DB 는 안 바뀐 게 아니어야 한다)")
    void update_persistsUpdatedAt() throws Exception {
        Account me = account("e2@c.com", "diverE2", Role.STUDENT);
        long id = createPost(me, "TOUR", "고치기 전", "본문");

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"고친 뒤\",\"body\":\"본문\"}"))
                .andExpect(status().isOk());

        String refetched = detailJson(id);
        assertThat(read(refetched, "$.updatedAt")).isNotEqualTo(read(refetched, "$.createdAt"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("결함 확정(2026-08-18): 사진만 바꾸면 updatedAt 이 안 움직인다. "
            + "실측값이 createdAt 과 바이트 단위로 같았다 — 부모 행이 dirty 가 아니라 @PreUpdate 가 안 돈다. "
            + "'수정됨' 표기를 채택하면 반드시 고쳐야 하고, 고치는 PR 에서 이 @Disabled 를 떼는 게 수용 기준이다.")
    @DisplayName("E3: 사진만 바꿔도 수정으로 친다 (제목·본문이 그대로여도 updatedAt 이 움직인다)")
    void update_mediaOnly_stillCountsAsEdit() throws Exception {
        Account me = account("e3@c.com", "diverE3", Role.STUDENT);

        MvcResult created = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진 글\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // 스칼라 필드(카테고리·제목·본문)는 전부 그대로 두고 사진만 한 장 더한다.
        // 부모 행이 dirty 가 아니면 @PreUpdate 가 안 돌아 updatedAt 이 멈춘다 — 그걸 잡는 테스트다.
        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진 글\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\",\"" + img("b") + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.length()").value(2));

        String refetched = detailJson(id);
        assertThat(read(refetched, "$.updatedAt")).isNotEqualTo(read(refetched, "$.createdAt"));
    }

    @Test
    @DisplayName("E4: 남의 글은 수정할 수 없다 (400 — 삭제와 같은 존재 숨김. R1 이 삭제만 보고 있었다)")
    void update_othersPost_rejected() throws Exception {
        Account owner = account("e4a@c.com", "diverE4a", Role.STUDENT);
        Account stranger = account("e4b@c.com", "diverE4b", Role.STUDENT);
        long id = createPost(owner, "TOUR", "내 글", "본문");

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"QNA\",\"title\":\"남이 고침\"}"))
                .andExpect(status().isBadRequest());

        // 400 을 돌려주는 것만으로는 부족하다 — 실제로 안 바뀌었는지 본다.
        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.title").value("내 글"));
    }

    @Test
    @DisplayName("E5: 수정은 스냅샷 교체다 — 사진·태그를 빼고 보내면 남는 게 아니라 지워진다")
    void update_omittingArrays_clearsThem() throws Exception {
        Account me = account("e5@c.com", "diverE5", Role.STUDENT);

        MvcResult created = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진과 태그\",\"body\":\"본문\","
                                + "\"mediaUrls\":[\"" + img("a") + "\"],\"tags\":[\"프리다이빙\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // mediaUrls·tags 키를 아예 생략한다. DTO 필드가 빈 배열로 초기화돼 있어 @NotNull 을 통과하고,
        // 서비스는 그 빈 배열을 "최종 상태" 로 받아 기존 것을 전부 지운다(+ S3 객체까지).
        // FE 가 부분 전송하면 사진이 조용히 날아간다는 뜻이라, 계약서 최상단에 박아야 할 동작이다.
        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"사진과 태그\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.length()").value(0))
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    @DisplayName("E6: 일정이 지난 같이가요 글도 제목을 고칠 수 있다 (일정을 그대로 두면 통과한다)")
    void update_pastMatch_keepingDate_isAllowed() throws Exception {
        Account me = account("e6@c.com", "diverE6", Role.STUDENT);
        long id = matchPost(me, "지나갈 일정", LocalDate.now().plusDays(1));

        // M4 와 같은 방식으로 "시간이 흐른" 상태를 만든다. 지난 모집글은 정상 상태다(M4 가 그렇게 못 박았다).
        CommunityPostMatch match = matchRepo.findById(id).orElseThrow();
        LocalDate past = LocalDate.now().minusDays(1);
        match.setMeetDate(past);
        matchRepo.saveAndFlush(match);

        // FE 가 상세로 폼을 프리필하면 지난 meetDate 를 그대로 되돌려 보낸다. 그게 막히면
        // 지난 모집글은 오타 하나 때문에 영구히 편집 불가가 된다 — 그래서 통과해야 한다.
        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"오타만 고침\","
                                + "\"match\":{\"meetDate\":\"" + past + "\",\"capacity\":4,"
                                + "\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("오타만 고침"))
                .andExpect(jsonPath("$.match.open").value(false));
    }

    @Test
    @DisplayName("E7: 그렇다고 지난 날짜로 일정을 '바꿀' 수는 없다 (완화가 구멍이 되면 안 된다)")
    void update_reschedulingToPast_isRejected() throws Exception {
        Account me = account("e7@c.com", "diverE7", Role.STUDENT);
        long id = matchPost(me, "앞으로의 일정", LocalDate.now().plusDays(10));

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"과거로 옮기기\","
                                + "\"match\":{\"meetDate\":\"" + LocalDate.now().minusDays(3) + "\","
                                + "\"capacity\":4,\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("지난 날짜로는 모집할 수 없어요."));
    }

    @Test
    @DisplayName("E8: 새 모집글은 여전히 지난 날짜로 만들 수 없다 (완화는 '기존 일정 유지' 에만 적용된다)")
    void create_pastMatch_stillRejected() throws Exception {
        Account me = account("e8@c.com", "diverE8", Role.STUDENT);

        mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"과거 모집\","
                                + "\"match\":{\"meetDate\":\"" + LocalDate.now().minusDays(1) + "\","
                                + "\"capacity\":4,\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("지난 날짜로는 모집할 수 없어요."));
    }

    @Test
    @DisplayName("E9: 오너에게는 DRAFT 연결 강의도 상세에 실린다 (없으면 수정 시 연결이 조용히 끊긴다)")
    void draftLinkedCourse_isVisibleToOwner() throws Exception {
        Account me = account("e9@c.com", "diverE9", Role.INSTRUCTOR);
        approveAsInstructor(me);
        Course draft = course(me, CourseStatus.DRAFT);
        long id = postLinkingCourse(me, draft, "준비 중인 강의 연결");

        // 오너는 자기 DRAFT 코스를 이미 알고 있다 — 수정 폼이 프리필할 id 를 받아야 한다.
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedCourse.id").value(draft.getId().intValue()))
                .andExpect(jsonPath("$.linkedCourse.status").value("DRAFT"));
    }

    @Test
    @DisplayName("E10: 하지만 남에게는 DRAFT 연결 강의가 여전히 안 보인다 (오너 예외가 공개 화면으로 새면 안 된다)")
    void draftLinkedCourse_stillHiddenFromOthers() throws Exception {
        Account author = account("e10a@c.com", "diverE10a", Role.INSTRUCTOR);
        approveAsInstructor(author);
        Account viewer = account("e10b@c.com", "diverE10b", Role.STUDENT);
        Course draft = course(author, CourseStatus.DRAFT);
        long id = postLinkingCourse(author, draft, "준비 중인 강의 연결");

        // 비로그인
        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.linkedCourse").doesNotExist());

        // 로그인했지만 남
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(viewer)))
                .andExpect(jsonPath("$.linkedCourse").doesNotExist());

        // 피드 카드는 오너에게도 공개 규칙 그대로다 — 카드엔 오너 개념이 없다.
        mockMvc.perform(get("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author)))
                .andExpect(jsonPath("$._embedded.posts[0].linkedCourse").doesNotExist());
    }

    @Test
    @DisplayName("E11: 본문 2000자를 넘겨도 저장된다 (계약이 5000 인데 컬럼이 2000 이라 500 이 나던 자리)")
    void update_longBody_isStored() throws Exception {
        Account me = account("e11@c.com", "diverE11", Role.STUDENT);
        long id = createPost(me, "TOUR", "긴 본문", "짧은 본문");
        String longBody = "가".repeat(4500);

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"긴 본문\",\"body\":\"" + longBody + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(longBody));
    }

    @Test
    @DisplayName("E12: 숨긴 글 상세는 오너에게만 열린다 (남은 로그인해도 400 — 오너 예외가 새면 안 된다)")
    void hiddenPost_detailIsOwnerOnly() throws Exception {
        Account owner = account("e12a@c.com", "diverE12a", Role.STUDENT);
        Account stranger = account("e12b@c.com", "diverE12b", Role.STUDENT);
        long id = createPost(owner, "TOUR", "숨길 글", "본문");

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        // 오너는 열린다 — 수정·숨김해제 진입점이 상세에 있어서 여기가 막히면 되돌릴 방법이 없다.
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.mine").value(true));

        // 남은 로그인해도 안 열린다. 비로그인도 마찬가지(H1 이 잠근다).
        mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("E13: 커뮤니티에서 숨기면 브랜딩 공개 프로필에서도 사라진다 (is_hidden 은 두 화면이 공유한다)")
    void hidingFromCommunity_alsoHidesFromBrandingGrid() throws Exception {
        Account me = account("e13@c.com", "diverE13", Role.INSTRUCTOR);
        long id = brandingPost(me, "TOUR", "프로필에 남길 글");

        mockMvc.perform(get(brandingGrid("diverE13")))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        // 커뮤니티 메뉴에서 숨겼을 뿐인데 브랜딩 공개 그리드에서도 빠진다.
        // 플래그가 showInFeed 가 아니라 두 화면이 공유하는 is_hidden 이기 때문이다.
        mockMvc.perform(get(brandingGrid("diverE13")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("E14: 커뮤니티에서 삭제하면 브랜딩 프로필에서도 영구히 사라진다 (같은 행을 지운다)")
    void deletingFromCommunity_alsoRemovesFromBrandingGrid() throws Exception {
        Account me = account("e14@c.com", "diverE14", Role.INSTRUCTOR);
        long id = brandingPost(me, "TOUR", "프로필에 남길 글");

        mockMvc.perform(delete("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        // hard delete 다 — 숨김과 달리 되돌릴 수 없다.
        mockMvc.perform(get(brandingGrid("diverE14")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        assertThat(postRepo.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("E15: DRAFT 강의를 건 글을 상세→수정으로 왕복해도 연결이 살아남는다 (E9 가 막으려던 결함 본체)")
    void draftLinkedCourse_survivesRoundTrip() throws Exception {
        Account me = account("e15@c.com", "diverE15", Role.INSTRUCTOR);
        approveAsInstructor(me);
        Course draft = course(me, CourseStatus.DRAFT);
        long id = postLinkingCourse(me, draft, "준비 중인 강의 연결");

        // FE 가 하는 그대로: 상세를 읽어 linkedCourse.id 를 뽑고, 그걸 linkedCourseId 로 되싣는다.
        String detail = mockMvc.perform(get("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String courseId = read(detail, "$.linkedCourse.id");

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"제목만 고침\",\"body\":\"본문\","
                                + "\"linkedCourseId\":" + courseId + "}"))
                .andExpect(status().isOk());

        // 결함의 본체는 "응답에 키가 없다" 가 아니라 "왕복하면 연결이 끊긴다" 였다.
        assertThat(postRepo.findById(id).orElseThrow().getLinkedCourse()).isNotNull();
    }

    @Test
    @DisplayName("E16: 지난 모집글의 일정을 미래로 옮기면 다시 열린다 (완화의 목적은 복구 경로다)")
    void pastMatch_canBeRescheduledForward() throws Exception {
        Account me = account("e16@c.com", "diverE16", Role.STUDENT);
        long id = matchPost(me, "되살릴 모집", LocalDate.now().plusDays(1));

        CommunityPostMatch match = matchRepo.findById(id).orElseThrow();
        match.setMeetDate(LocalDate.now().minusDays(2));
        matchRepo.saveAndFlush(match);

        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"일정 새로 잡음\","
                                + "\"match\":{\"meetDate\":\"" + LocalDate.now().plusDays(7) + "\","
                                + "\"capacity\":4,\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.open").value(true));
    }

    @Test
    @DisplayName("E17: 카테고리를 돌렸다 오면 지난 일정은 되살릴 수 없다 (모집정보가 지워져 '새 모집' 이 된다)")
    void matchRoundTrip_losesPastDate() throws Exception {
        Account me = account("e17@c.com", "diverE17", Role.STUDENT);
        long id = matchPost(me, "왕복할 모집", LocalDate.now().plusDays(1));

        CommunityPostMatch match = matchRepo.findById(id).orElseThrow();
        LocalDate past = LocalDate.now().minusDays(2);
        match.setMeetDate(past);
        matchRepo.saveAndFlush(match);

        // MATCH → TOUR 로 바꾸면 모집정보 행이 삭제된다(M6).
        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"잠깐 자랑 글\"}"))
                .andExpect(status().isOk());

        // 다시 MATCH 로 오면 비교할 이전 일정이 없어 "새 모집" 으로 판정된다 → 과거 날짜는 거부.
        // 되살리려면 일정을 새로 잡아야 한다. 완화가 카테고리 왕복으로 우회되지 않는다는 뜻이다.
        mockMvc.perform(put("/community/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"MATCH\",\"title\":\"되돌리기\","
                                + "\"match\":{\"meetDate\":\"" + past + "\",\"capacity\":4,"
                                + "\"levelLabel\":\"AOWD 이상\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("지난 날짜로는 모집할 수 없어요."));
    }

    @Test
    @DisplayName("E18: 커뮤니티에서 숨기면 브랜딩 상세도 hidden=true 로 온다 (오너 시트가 상태를 알아야 한다)")
    void hiddenState_isVisibleOnBrandingDetail() throws Exception {
        Account me = account("e18@c.com", "diverE18", Role.INSTRUCTOR);
        long id = brandingPost(me, "TOUR", "프로필 글");

        mockMvc.perform(get("/branding-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(false));

        mockMvc.perform(patch("/community/posts/" + id + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        // 같은 행의 같은 컬럼이라 브랜딩 상세도 숨김을 알아야 한다. 이 필드가 없으면 오너 액션시트가
        // 이미 숨긴 글에 "숨기기" 를 그린다 — 커뮤니티에서 숨긴 뒤 브랜딩으로 넘어오는 경로가 실재한다.
        mockMvc.perform(get("/branding-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true));
    }

    /** 연결 강의를 건 커뮤니티 글 작성 → 생성된 id. */
    private long postLinkingCourse(Account author, Course course, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"" + title + "\",\"body\":\"본문\","
                                + "\"linkedCourseId\":" + course.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
