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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브랜딩 게시물 — 그리드·상세·오너 CRUD.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / O* 정렬 / H* 숨김 / L* 강의 연결 / V* 검증 / R* 권한.
 *
 * <p>이 피처에서 틀리기 쉬운 세 가지를 특히 못 박는다 — <b>숨김은 삭제와 다르다</b>(되돌릴 수 있고 공개
 * 경로에서만 빠진다), <b>정렬은 서버가 고정한다</b>(클라이언트가 못 바꾼다), <b>남의 글은 400</b>(존재 숨김).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrandingPostUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired BrandingPostJpaRepo postRepo;
    @Autowired CourseJpaRepo courseRepo;

    /** 로컬 stub 이 발급하는 URL prefix — 저장 허용 URL 검증의 기준이다. */
    @Value("${pungdong.storage.local.base-url:http://localhost:8080}")
    String localBaseUrl;

    @AfterEach
    void cleanUp() {
        postRepo.deleteAll();
        brandingRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

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

    private URI publicPosts(String nickName) {
        return URI.create("/instructors/" + URLEncoder.encode(nickName, StandardCharsets.UTF_8) + "/posts");
    }

    private long createPost(Account owner, String caption, String... images) throws Exception {
        StringBuilder urls = new StringBuilder();
        for (int i = 0; i < images.length; i++) {
            urls.append(i > 0 ? "," : "").append('"').append(img(images[i])).append('"');
        }
        MvcResult result = mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[" + urls + "],\"caption\":\"" + caption + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 첫 게시물 작성이 프로필까지 만든다 (디자인상 이게 주 진입 경로다)")
    void firstPost_createsProfileToo() throws Exception {
        Account me = account("s1@test.com", "diverP1", Role.STUDENT);

        createPost(me, "첫 게시물", "a");

        assertThat(brandingRepo.findByAccountId(me.getId())).isPresent();
    }

    @Test
    @DisplayName("S2: 공개 그리드 카드에 썸네일과 미디어 장수가 실린다 (2장 이상이면 FE 가 캐로셀 뱃지를 그린다)")
    void publicGrid_returnsThumbnailAndCount() throws Exception {
        Account me = account("s2@test.com", "diverP2", Role.STUDENT);
        createPost(me, "캐로셀", "a", "b", "c");

        mockMvc.perform(get(publicPosts("diverP2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].mediaCount").value(3))
                .andExpect(jsonPath("$._embedded.posts[0].thumbnailUrl").value(img("a")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("S3: 상세에 캐로셀·본문·태그·위치·작성자가 담기고 상대시간이 아니라 UTC 시각이 온다")
    void detail_returnsFullPayload() throws Exception {
        Account me = account("s3@test.com", "diverP3", Role.STUDENT);
        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"caption\":\"문섬 다녀왔어요\","
                                + "\"tags\":[\"제주다이빙\",\"문섬\"],\"locationLabel\":\"제주 서귀포 문섬\"}"))
                .andExpect(status().isOk());

        long postId = postRepo.findAll().get(0).getId();

        mockMvc.perform(get("/branding-posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value("문섬 다녀왔어요"))
                .andExpect(jsonPath("$.tags[0]").value("제주다이빙"))
                .andExpect(jsonPath("$.locationLabel").value("제주 서귀포 문섬"))
                .andExpect(jsonPath("$.author.nickName").value("diverP3"))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    @DisplayName("S4: 수정은 사진·태그를 통째로 교체한다 (스냅샷)")
    void update_replacesMediaAndTags() throws Exception {
        Account me = account("s4@test.com", "diverP4", Role.STUDENT);
        long postId = createPost(me, "원본", "a", "b");

        mockMvc.perform(put("/branding/me/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("c") + "\"],\"caption\":\"수정본\",\"tags\":[\"새태그\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.length()").value(1))
                .andExpect(jsonPath("$.media[0].url").value(img("c")))
                .andExpect(jsonPath("$.caption").value("수정본"))
                .andExpect(jsonPath("$.tags[0]").value("새태그"));
    }

    @Test
    @DisplayName("S5: 삭제하면 공개 목록·상세에서 사라진다")
    void delete_removesPost() throws Exception {
        Account me = account("s5@test.com", "diverP5", Role.STUDENT);
        long postId = createPost(me, "지울 글", "a");

        mockMvc.perform(delete("/branding/me/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/branding-posts/" + postId)).andExpect(status().isBadRequest());
        assertThat(postRepo.findAll()).isEmpty();
    }

    /* ════════════════ O — 정렬 ════════════════ */

    @Test
    @DisplayName("O1: 고정한 글이 최신 글보다 위에 온다 (고정 → 최신순)")
    void pinnedComesFirst() throws Exception {
        Account me = account("o1@test.com", "diverP6", Role.STUDENT);
        long older = createPost(me, "예전 글", "a");
        createPost(me, "최신 글", "b");

        mockMvc.perform(patch("/branding/me/posts/" + older + "/pin")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinned\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(publicPosts("diverP6")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].id").value((int) older))
                .andExpect(jsonPath("$._embedded.posts[0].pinned").value(true));
    }

    @Test
    @DisplayName("O2: 클라이언트가 sort 를 보내도 서버 정렬이 유지된다 (임의 정렬로 못 바꾼다)")
    void clientSortIsIgnored() throws Exception {
        Account me = account("o2@test.com", "diverP7", Role.STUDENT);
        long older = createPost(me, "예전 글", "a");
        createPost(me, "최신 글", "b");

        mockMvc.perform(patch("/branding/me/posts/" + older + "/pin")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinned\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URI.create("/instructors/diverP7/posts?sort=id,asc&size=999")))
                .andExpect(status().isOk())
                // 고정 글이 여전히 첫 번째다 — sort 파라미터가 먹혔다면 순서가 뒤집혔을 것
                .andExpect(jsonPath("$._embedded.posts[0].id").value((int) older))
                // size 도 상한(50)으로 잘린다
                .andExpect(jsonPath("$.page.size").value(50));
    }

    /* ════════════════ H — 숨김 ════════════════ */

    @Test
    @DisplayName("H1: 숨긴 글은 공개 목록·상세에서 빠지지만 오너 목록엔 남는다 (삭제가 아니라 되돌릴 수 있는 상태)")
    void hiddenPost_disappearsPubliclyButStaysForOwner() throws Exception {
        Account me = account("h1@test.com", "diverP8", Role.STUDENT);
        long postId = createPost(me, "숨길 글", "a");

        mockMvc.perform(patch("/branding/me/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(publicPosts("diverP8")))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/branding-posts/" + postId)).andExpect(status().isBadRequest());

        mockMvc.perform(get("/branding/me/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].hidden").value(true));
    }

    @Test
    @DisplayName("H2: 숨김을 풀면 다시 공개된다")
    void unhide_restoresPost() throws Exception {
        Account me = account("h2@test.com", "diverP9", Role.STUDENT);
        long postId = createPost(me, "숨겼다 풀 글", "a");
        String token = tokenFor(me);

        mockMvc.perform(patch("/branding/me/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/branding/me/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/branding-posts/" + postId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("H3: 숨긴 글은 프로필 헤더의 게시물 수에서도 빠진다")
    void hiddenPost_isNotCountedInStats() throws Exception {
        Account me = account("h3@test.com", "diverP10", Role.STUDENT);
        long hidden = createPost(me, "숨길 글", "a");
        createPost(me, "보이는 글", "b");

        mockMvc.perform(patch("/branding/me/posts/" + hidden + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URI.create("/instructors/diverP10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.posts").value(1));
    }

    @Test
    @DisplayName("H4: 오너 본인은 숨긴 글의 상세를 볼 수 있다 (상세에서 바로 '다시 공개'를 누를 수 있어야 한다)")
    void owner_canOpenOwnHiddenPostDetail() throws Exception {
        Account me = account("h4@test.com", "diverP21", Role.STUDENT);
        long postId = createPost(me, "숨긴 글", "a");
        String token = tokenFor(me);

        mockMvc.perform(patch("/branding/me/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        // 비로그인은 400, 오너는 200 — 같은 URL 이 보는 사람에 따라 갈린다
        mockMvc.perform(get("/branding-posts/" + postId)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/branding-posts/" + postId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) postId));
    }

    @Test
    @DisplayName("H5: 남의 숨긴 글은 로그인해도 400 (오너 예외는 자기 글에만 적용된다)")
    void othersHiddenPost_stillHidden() throws Exception {
        Account owner = account("h5a@test.com", "diverP22", Role.STUDENT);
        Account stranger = account("h5b@test.com", "diverP23", Role.STUDENT);
        long postId = createPost(owner, "숨긴 글", "a");

        mockMvc.perform(patch("/branding/me/posts/" + postId + "/visibility")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/branding-posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ L — 강의 연결 ════════════════ */

    private Course course(Account instructor, CourseStatus status) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title("문섬 어드밴스드").kind(CourseKind.CERTIFICATION)
                .disciplineCode("FREEDIVING").totalRounds(1).price(680000).status(status)
                .build());
    }

    @Test
    @DisplayName("L1: 내 강의를 연결하면 상세에 강의 카드가 함께 온다")
    void linkedCourse_isReturned() throws Exception {
        Account me = account("l1@test.com", "diverP11", Role.INSTRUCTOR);
        Course mine = course(me, CourseStatus.OPEN);

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"linkedCourseId\":" + mine.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedCourse.id").value(mine.getId().intValue()))
                .andExpect(jsonPath("$.linkedCourse.status").value("OPEN"));
    }

    /**
     * ⚠️ <b>이 테스트는 2026-08-18 에 뒤집혔다.</b> 원래는 "오너 응답에서도 DRAFT 가 빠진다" 를 사양으로
     * 단언하고 있었다. 그게 <b>무음 데이터 손실의 원인</b>이라 바꿨다 —
     * 이 상세가 오너의 수정 폼 프리필 소스인데 키가 없으면 폼이 {@code linkedCourseId} 를 못 채우고,
     * 수정이 스냅샷 교체라 저장하는 순간 연결이 조용히 끊긴다(사용자는 오타만 고쳤다).
     * 커뮤니티가 같은 결함을 먼저 고쳤고, 같은 성격의 결정을 도메인마다 다르게 갈 이유가 없어 맞췄다.
     * <b>공개 쪽 규칙은 그대로다</b> — L3 가 그걸 지킨다. 되돌리려면 두 테스트를 함께 봐야 한다.
     */
    @Test
    @DisplayName("L2: 미공개(DRAFT) 강의도 오너 응답에는 실린다 (없으면 수정 시 연결이 조용히 끊긴다)")
    void draftCourse_isIncludedForOwner() throws Exception {
        Account me = account("l2@test.com", "diverP12", Role.INSTRUCTOR);
        Course draft = course(me, CourseStatus.DRAFT);

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"linkedCourseId\":" + draft.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedCourse.id").value(draft.getId().intValue()))
                .andExpect(jsonPath("$.linkedCourse.status").value("DRAFT"));
    }

    @Test
    @DisplayName("L4: 상세가 카테고리·제목을 준다 (수정 폼이 되실을 값을 받아야 저장 때 안 지워진다)")
    void detail_carriesCategoryAndTitle_soEditCanRoundTrip() throws Exception {
        Account me = account("l4@test.com", "diverP15", Role.INSTRUCTOR);

        MvcResult created = mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"category\":\"TOUR\","
                                + "\"title\":\"문섬 다이빙\",\"caption\":\"본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("TOUR"))
                .andExpect(jsonPath("$.title").value("문섬 다이빙"))
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // 상세로 프리필 → 그대로 되실어 저장. 값을 못 받으면 여기서 보낼 게 없어 지워진다.
        String detail = mockMvc.perform(get("/branding-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("TOUR"))
                .andExpect(jsonPath("$.title").value("문섬 다이빙"))
                // ⚠️ 인자 없는 getContentAsString() 은 기본 charset 으로 읽어 한글이 깨진다.
                // 깨진 값을 그대로 되실으면 "라운드트립이 됐다" 는 착각 속에 제목이 망가진 채 저장된다.
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // JsonPath.read 는 제네릭이라 String.valueOf 에 바로 넘기면 char[] 오버로드로 추론돼 터진다.
        Object categoryValue = com.jayway.jsonpath.JsonPath.read(detail, "$.category");
        Object titleValue = com.jayway.jsonpath.JsonPath.read(detail, "$.title");
        String category = String.valueOf(categoryValue);
        String title = String.valueOf(titleValue);

        mockMvc.perform(put("/branding/me/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"category\":\"" + category + "\","
                                + "\"title\":\"" + title + "\",\"caption\":\"본문 고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("TOUR"))
                .andExpect(jsonPath("$.title").value("문섬 다이빙"));
    }

    @Test
    @DisplayName("L5: 반대로 안 되실으면 지워진다 (스냅샷 교체라 생략 = 비우기 — FE 가드가 필요한 이유)")
    void detail_omittingCategoryAndTitle_clearsThem() throws Exception {
        Account me = account("l5@test.com", "diverP16", Role.INSTRUCTOR);

        MvcResult created = mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"category\":\"TOUR\","
                                + "\"title\":\"지워질 제목\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // 두 키를 뺀 수정 — 서버는 @NotNull 없이 무조건 덮어쓰므로 null 이 된다.
        // 사양이 아니라 **스냅샷 교체의 귀결**이다. FE 는 응답값을 되실어 이걸 피해야 한다.
        mockMvc.perform(put("/branding/me/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"caption\":\"본문만 고침\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    @DisplayName("L3: 그래도 남·비로그인에게는 DRAFT 강의가 안 보인다 (오너 예외가 공개 화면으로 새면 안 된다)")
    void draftCourse_isStillHiddenFromPublic() throws Exception {
        Account me = account("l3@test.com", "diverP13", Role.INSTRUCTOR);
        Account stranger = account("l3b@test.com", "diverP14", Role.STUDENT);
        Course draft = course(me, CourseStatus.DRAFT);

        MvcResult created = mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"linkedCourseId\":" + draft.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/branding-posts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedCourse").doesNotExist());

        mockMvc.perform(get("/branding-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedCourse").doesNotExist());
    }

    @Test
    @DisplayName("L3: 남의 강의는 연결할 수 없다 (400)")
    void othersCourse_cannotBeLinked() throws Exception {
        Account me = account("l3a@test.com", "diverP13", Role.INSTRUCTOR);
        Account other = account("l3b@test.com", "diverP14", Role.INSTRUCTOR);
        Course theirs = course(other, CourseStatus.OPEN);

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"],\"linkedCourseId\":" + theirs.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("내 강의만 연결할 수 있어요."));
    }

    /* ════════════════ V — 검증 ════════════════ */

    @Test
    @DisplayName("V1: 업로드로 받지 않은 외부 이미지 주소는 거부한다 (본문에 임의 URL 을 심지 못하게)")
    void foreignImageUrl_isRejected() throws Exception {
        Account me = account("v1@test.com", "diverP15", Role.STUDENT);

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"https://evil.example.com/x.jpg\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("업로드로 받은 이미지 주소만 사용할 수 있어요."));

        assertThat(postRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V2: 사진이 한 장도 없으면 400")
    void noMedia_returns400() throws Exception {
        Account me = account("v2@test.com", "diverP16", Role.STUDENT);

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 남의 게시물을 수정·삭제하려 하면 400 (403 이 아니라 존재 자체를 숨긴다)")
    void othersPost_isHidden() throws Exception {
        Account owner = account("r1a@test.com", "diverP17", Role.STUDENT);
        Account stranger = account("r1b@test.com", "diverP18", Role.STUDENT);
        long postId = createPost(owner, "내 글", "a");

        mockMvc.perform(delete("/branding/me/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger)))
                .andExpect(status().isBadRequest());

        assertThat(postRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("R2: 비로그인은 공개 그리드·상세를 볼 수 있지만 작성은 401")
    void anonymous_canReadButNotWrite() throws Exception {
        Account me = account("r2@test.com", "diverP19", Role.STUDENT);
        createPost(me, "공개 글", "a");

        mockMvc.perform(get(publicPosts("diverP19"))).andExpect(status().isOk());
        mockMvc.perform(post("/branding/me/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaUrls\":[\"" + img("a") + "\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R3: 프로필이 아직 없는 계정의 오너 목록은 빈 페이지다 (400 이 아니다)")
    void ownerGrid_withoutProfile_isEmptyPage() throws Exception {
        Account me = account("r3@test.com", "diverP20", Role.STUDENT);

        mockMvc.perform(get("/branding/me/posts").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }
}
