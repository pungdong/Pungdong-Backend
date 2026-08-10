package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountService;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.dto.*;
import com.diving.pungdong.global.config.RestDocsConfiguration;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.global.security.UserAccount;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
// pathParameters 스니펫은 URL 템플릿 정보를 요구하므로 MockMvcRequestBuilders 가 아니라 이걸 써야 한다.
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브랜딩 페이지 REST Docs 스니펫 — HTTP 배선 + 문서 생성만 검증한다(비즈니스 규칙은
 * {@code usecase/BrandingUseCaseTest} 가 실 스택으로 본다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@ActiveProfiles("test")
@Import(RestDocsConfiguration.class)
class BrandingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockBean AccountService accountService;
    @MockBean BrandingService brandingService;

    private Account account() {
        Account account = Account.builder()
                .id(1L).email("diver@test.com").password("encoded").nickName("김다이버")
                .roles(Set.of(Role.INSTRUCTOR))
                .build();
        given(accountService.loadUserByUsername(String.valueOf(account.getId())))
                .willReturn(new UserAccount(account));
        return account;
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private BrandingProfileResponse publicProfile() {
        return BrandingProfileResponse.builder()
                .nickName("김다이버")
                .avatarUrl("https://cdn.plop.cool/profile-photo/abc.jpg")
                .tagline("12년차 프리·스쿠버 강사")
                .bio("제주·부산에서 정기 투어를 열어요.")
                .locationLabel("서울 · 부산")
                .isInstructor(true)
                .disciplineCodes(List.of("FREEDIVING"))
                .certs(List.of(BrandingProfileResponse.CertBadge.builder()
                        .disciplineCode("FREEDIVING").organizationCode("AIDA").build()))
                .records(List.of(RecordDto.builder()
                        .medal(Medal.GOLD).eventCode(RecordEventCode.CWT).value("-75m").build()))
                .stats(BrandingStats.builder().students(1284).build())
                .products(BrandingProducts.builder().lessons(8).build())
                .build();
    }

    private MyBrandingResponse myBranding() {
        return MyBrandingResponse.builder()
                .exists(true).isPublished(true)
                .nickName("김다이버")
                .avatarUrl("https://cdn.plop.cool/profile-photo/abc.jpg")
                .tagline("12년차 프리·스쿠버 강사")
                .bio("제주·부산에서 정기 투어를 열어요.")
                .locationLabel("서울 · 부산")
                .isInstructor(true)
                .disciplineCodes(List.of("FREEDIVING"))
                .certs(List.of(BrandingProfileResponse.CertBadge.builder()
                        .disciplineCode("FREEDIVING").organizationCode("AIDA").build()))
                .records(List.of(RecordDto.builder()
                        .medal(Medal.GOLD).eventCode(RecordEventCode.CWT).value("-75m").build()))
                .stats(BrandingStats.builder().students(1284).build())
                .products(BrandingProducts.builder().lessons(8).build())
                .reviewStatus(InstructorApplicationStatus.APPROVED)
                .approvedAt(OffsetDateTime.of(2026, 5, 13, 4, 21, 0, 0, ZoneOffset.UTC))
                .build();
    }

    /** 공개 응답의 필드 문서 — 오너 응답이 이걸 그대로 포함하므로 재사용한다. */
    private FieldDescriptor[] profileFields(String prefix) {
        return new FieldDescriptor[]{
                fieldWithPath(prefix + "nickName").description("공개 표시 이름 겸 URL 식별자"),
                fieldWithPath(prefix + "avatarUrl").description("프로필 사진 CDN URL (미설정이면 null)").optional(),
                fieldWithPath(prefix + "tagline").description("한 줄 소개 (유저가 비우면 null)").optional(),
                fieldWithPath(prefix + "bio").description("자기소개 (유저가 비우면 null)").optional(),
                fieldWithPath(prefix + "locationLabel").description("활동 지역 (유저가 비우면 null)").optional(),
                fieldWithPath(prefix + "isInstructor").description("인증마크 표시 여부 = 승인된 강사"),
                fieldWithPath(prefix + "disciplineCodes").description("승인 종목 코드 — 강사만, 아니면 키 없음").optional(),
                fieldWithPath(prefix + "certs[].disciplineCode").description("자격이 속한 종목").optional(),
                fieldWithPath(prefix + "certs[].organizationCode").description("발급 단체 코드").optional(),
                fieldWithPath(prefix + "certs[].organizationOther").description("단체가 OTHER 일 때 직접입력").optional(),
                fieldWithPath(prefix + "records[].medal").description("메달 GOLD|SILVER|BRONZE").optional(),
                fieldWithPath(prefix + "records[].eventCode").description("경기 세부종목 CWT|FIM|CNF|DYN|DNF|STA").optional(),
                fieldWithPath(prefix + "records[].value").description("기록 원문 — 단위가 종목마다 달라 문자열").optional(),
                fieldWithPath(prefix + "stats.students").description("누적 수강생 수 — 강사만").optional(),
                fieldWithPath(prefix + "products.lessons").description("공개 강의 수 — 강사만").optional(),
        };
    }

    @Test
    @DisplayName("공개 브랜딩 페이지 조회")
    void publicBrandingPage() throws Exception {
        given(brandingService.publicProfile(any())).willReturn(publicProfile());

        mockMvc.perform(get("/instructors/{nickName}", "김다이버"))
                .andExpect(status().isOk())
                .andDo(document("branding-public",
                        pathParameters(
                                parameterWithName("nickName").description("공개 식별자. percent-encoding 으로 전달")
                        ),
                        relaxedResponseFields(profileFields(""))
                ));
    }

    @Test
    @DisplayName("내 브랜딩 페이지 조회")
    void myBrandingPage() throws Exception {
        Account account = account();
        given(brandingService.myBranding(any())).willReturn(myBranding());

        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(account)))
                .andExpect(status().isOk())
                .andDo(document("branding-me",
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("access token (Bearer prefix 없음)")
                        ),
                        relaxedResponseFields(
                                fieldWithPath("exists").description("프로필 생성 여부. false 면 나머지 키가 없다"),
                                fieldWithPath("isPublished").description("공개 여부"),
                                fieldWithPath("reviewStatus").description("강사 검수 상태 — 신청 이력이 있을 때만").optional(),
                                fieldWithPath("approvedAt").description("승인 시각(UTC) — APPROVED 일 때만").optional()
                        ).and(profileFields(""))
                ));
    }

    @Test
    @DisplayName("내 브랜딩 페이지 부분 수정")
    void patchMyBranding() throws Exception {
        Account account = account();
        given(brandingService.updateMyBranding(any(), any())).willReturn(myBranding());

        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"12년차 프리·스쿠버 강사\"}"))
                .andExpect(status().isOk())
                .andDo(document("branding-me-update",
                        requestFields(
                                fieldWithPath("tagline").description("한 줄 소개 (최대 60자). 키를 빼면 변경 없음, null 이면 비우기").optional()
                        ),
                        relaxedResponseFields(profileFields(""))
                ));
    }

    @Test
    @DisplayName("내 브랜딩 페이지 공식 기록 교체")
    void replaceRecords() throws Exception {
        Account account = account();
        given(brandingService.replaceRecords(any(), any())).willReturn(myBranding());

        mockMvc.perform(put("/branding/me/records")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[{\"medal\":\"GOLD\",\"eventCode\":\"CWT\",\"value\":\"-75m\"}]}"))
                .andExpect(status().isOk())
                .andDo(document("branding-me-records",
                        requestFields(
                                fieldWithPath("records[].medal").description("GOLD|SILVER|BRONZE"),
                                fieldWithPath("records[].eventCode").description("CWT|FIM|CNF|DYN|DNF|STA"),
                                fieldWithPath("records[].value").description("기록 원문(최대 16자). 단위가 종목마다 달라 문자열")
                        ),
                        relaxedResponseFields(profileFields(""))
                ));
    }

    @Test
    @DisplayName("내 브랜딩 페이지 발행 토글")
    void publishMyBranding() throws Exception {
        Account account = account();
        given(brandingService.updatePublished(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .willReturn(myBranding());

        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk())
                .andDo(document("branding-me-publish",
                        requestFields(
                                fieldWithPath("published").description("공개 여부. 승인 게이트 없음 — 일반 유저도 가능")
                        ),
                        relaxedResponseFields(
                                fieldWithPath("isPublished").description("반영된 공개 여부")
                        )
                ));
    }
}
