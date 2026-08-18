package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountService;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.dto.*;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.global.config.RestDocsConfiguration;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.global.security.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 게시물 REST Docs 스니펫 — HTTP 배선·문서 생성만. 규칙은 {@code usecase/BrandingPostUseCaseTest} 가 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@ActiveProfiles("test")
@Import(RestDocsConfiguration.class)
class BrandingPostControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockBean AccountService accountService;
    @MockBean BrandingPostService postService;
    @MockBean BrandingService brandingService;

    private Account account() {
        Account account = Account.builder()
                .id(1L).email("diver@test.com").password("encoded").nickName("김다이버")
                .roles(Set.of(Role.INSTRUCTOR)).build();
        given(accountService.loadUserByUsername("1")).willReturn(new UserAccount(account));
        return account;
    }

    private String token(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private BrandingPostDetailResponse detail() {
        return BrandingPostDetailResponse.builder()
                .id(1201L)
                .author(BrandingPostDetailResponse.Author.builder()
                        .nickName("김다이버").avatarUrl("https://cdn.plop.cool/profile-photo/a.jpg").build())
                .media(List.of(BrandingPostDetailResponse.Media.builder()
                        .kind(BrandingMediaKind.PHOTO).url("https://cdn.plop.cool/branding/a.jpg").sortOrder(0).build()))
                .caption("제주 문섬 어드밴스드 4박5일")
                .tags(List.of("제주다이빙", "문섬"))
                .locationLabel("제주 서귀포 문섬")
                .createdAt(OffsetDateTime.of(2026, 8, 9, 12, 30, 0, 0, ZoneOffset.UTC))
                .pinned(true)
                .linkedCourse(LinkedCourseResponse.builder()
                        .id(77L).title("서귀포 문섬 어드밴스드").price(680000).status(CourseStatus.OPEN).build())
                .build();
    }

    @Test
    @DisplayName("공개 게시물 그리드 조회")
    void publicGrid() throws Exception {
        given(postService.publicGrid(any(), any())).willReturn(new PageImpl<>(
                List.of(BrandingPostCardResponse.builder()
                        .id(1201L).thumbnailUrl("https://cdn.plop.cool/branding/a.jpg")
                        .mediaCount(5).pinned(true).build()),
                PageRequest.of(0, 18), 1));

        mockMvc.perform(get("/instructors/{nickName}/posts", "김다이버"))
                .andExpect(status().isOk())
                .andDo(document("branding-posts",
                        pathParameters(parameterWithName("nickName").description("공개 식별자(percent-encoding)")),
                        relaxedResponseFields(
                                fieldWithPath("_embedded.posts[].id").description("게시물 id"),
                                fieldWithPath("_embedded.posts[].thumbnailUrl").description("대표 사진(첫 장)").optional(),
                                fieldWithPath("_embedded.posts[].mediaCount").description("사진 장수. 2 이상이면 캐로셀"),
                                fieldWithPath("_embedded.posts[].pinned").description("상단 고정 여부"),
                                fieldWithPath("page.totalElements").description("전체 게시물 수")
                        )));
    }

    @Test
    @DisplayName("게시물 상세 조회")
    void publicDetail() throws Exception {
        given(postService.detail(any(), any())).willReturn(detail());

        mockMvc.perform(get("/branding-posts/{postId}", 1201L))
                .andExpect(status().isOk())
                .andDo(document("branding-post-public",
                        pathParameters(parameterWithName("postId").description("게시물 id")),
                        relaxedResponseFields(
                                fieldWithPath("id").description("게시물 id"),
                                fieldWithPath("author.nickName").description("작성자 표시 이름"),
                                fieldWithPath("author.avatarUrl").description("작성자 프로필 사진").optional(),
                                fieldWithPath("media[].kind").description("PHOTO (VIDEO 는 스키마 자리만 예약)"),
                                fieldWithPath("media[].url").description("사진 CDN URL"),
                                fieldWithPath("media[].sortOrder").description("표시 순서. 0번이 대표"),
                                fieldWithPath("caption").description("본문").optional(),
                                fieldWithPath("tags").description("태그"),
                                fieldWithPath("locationLabel").description("위치").optional(),
                                fieldWithPath("createdAt").description("작성 시각(UTC). 상대시간 변환은 FE 가 한다"),
                                fieldWithPath("pinned").description("상단 고정 여부"),
                                fieldWithPath("linkedCourse.id").description("연결 강의 id").optional(),
                                fieldWithPath("linkedCourse.title").description("강의명").optional(),
                                fieldWithPath("linkedCourse.price").description("가격").optional(),
                                fieldWithPath("linkedCourse.status").description("OPEN|CLOSED. DRAFT·삭제면 객체 자체가 없다").optional()
                        )));
    }

    @Test
    @DisplayName("게시물 작성")
    void createPost() throws Exception {
        Account account = account();
        given(postService.create(any(), any())).willReturn(detail());

        mockMvc.perform(post("/branding/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, token(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"TOUR\",\"title\":\"제주 문섬 다이빙\","
                                + "\"mediaUrls\":[\"https://cdn.plop.cool/branding/a.jpg\"],"
                                + "\"caption\":\"제주 문섬\",\"tags\":[\"제주다이빙\"],"
                                + "\"locationLabel\":\"제주 서귀포 문섬\",\"linkedCourseId\":77}"))
                .andExpect(status().isOk())
                .andDo(document("branding-post-create",
                        requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION).description("access token")),
                        requestFields(
                                fieldWithPath("category").description("TOUR|TRAINING|MATCH|QNA. **필수**. 이 경로는 구버전 앱 호환용이고, 신규 작성은 `POST /community/posts` + `showOnProfile:true`"),
                                fieldWithPath("title").description("제목 2~100자. **필수**(2026-08-18~)"),
                                fieldWithPath("mediaUrls").description("업로드로 받은 CDN URL 1~10개. **배열 순서가 표시 순서**이고 0번이 썸네일"),
                                fieldWithPath("caption").description("본문(최대 2000자)").optional(),
                                fieldWithPath("tags").description("태그 최대 10개, 각 30자").optional(),
                                fieldWithPath("locationLabel").description("위치(최대 60자)").optional(),
                                fieldWithPath("linkedCourseId").description("연결할 **내** 강의 id. 남의 강의면 400").optional()
                        ),
                        relaxedResponseFields(fieldWithPath("id").description("생성된 게시물 id"))));
    }

    // 숨김 토글 문서는 여기 없다 — 엔드포인트를 커뮤니티로 합쳤다
    // (PATCH /community/posts/{id}/visibility). 숨김은 두 표면에 함께 걸리는 전역 스위치라
    // 경로가 둘이면 규칙이 갈린다(이 경로는 프로필 글만 통과시켰고 어드민 조치 확인이 없었다).
}
