package com.diving.pungdong.usecase;

import com.diving.pungdong.account.*;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.branding.BrandingPostJpaRepo;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커뮤니티 — 피드·상세·작성.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / X* 노출 방향 / F* 필터 / M* 같이가요 / A* 작성자 합성 / V* 검증 / H* 숨김 / R* 권한.
 *
 * <p>이 피처에서 가장 틀리기 쉬운 건 <b>노출 방향</b>이다 — 브랜딩에 올리면 커뮤니티에도 가지만
 * 커뮤니티에 올린 글은 브랜딩에 가지 않는다. X* 가 그걸 양방향으로 못 박는다.
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

    @Value("${pungdong.storage.local.base-url:http://localhost:8080}")
    String localBaseUrl;

    /**
     * 삭제 순서는 FK 방향의 역순이다 — 자식(모집정보·좋아요·북마크·댓글)을 먼저 지우지 않으면
     * 게시물 삭제가 제약 위반으로 터지고, 그 예외 때문에 다음 테스트에 행이 남아 연쇄로 깨진다.
     */
    @AfterEach
    void cleanUp() {
        matchRepo.deleteAll();
        likeRepo.deleteAll();
        bookmarkRepo.deleteAll();
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
                                + "\",\"caption\":\"" + caption + "\"}"))
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
}
