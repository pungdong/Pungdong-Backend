package com.diving.pungdong.usecase;

import com.diving.pungdong.account.*;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브랜딩 페이지(강사) / 내 프로필(일반) — 프로필 조회·편집 use-case.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / C* 생성 규칙 / I* 강사·일반 분기 / E* 닉네임 인코딩 / V* 검증 / R* 권한.
 *
 * <p>실 H2 + 실 시큐리티 필터체인. 이 피처의 두 핵심 규칙을 여기서 못 박는다:
 * <ol>
 *   <li><b>조회는 생성하지 않는다</b> — 생성은 첫 쓰기가 한다(GET 에 side effect 를 넣지 않는다)</li>
 *   <li><b>강사 한정 필드는 일반 유저 응답에서 키 자체가 빠진다</b>(D2)</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrandingUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;

    @AfterEach
    void cleanUp() {
        applicationRepo.deleteAll();
        brandingRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private Account account(String email, String nickName, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(role)))
                .isDeleted(false)
                .build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    /** 승인된 강사 신청 + 자격증 1건 — certs·disciplineCodes·approvedAt 의 출처. */
    private void approveAsInstructor(Account account, String disciplineCode, String organizationCode) {
        InstructorApplication application = InstructorApplication.builder()
                .account(account)
                .disciplineCode(disciplineCode)
                .status(InstructorApplicationStatus.APPROVED)
                .reviewedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        application.getCertificates().add(ApplicationCertificate.builder()
                .application(application)
                .organizationCode(organizationCode)
                .sortOrder(0)
                .build());
        applicationRepo.save(application);
    }

    /** 프로필을 만들고 발행 상태로 둔다(첫 쓰기 = 생성). */
    private void createPublishedBranding(Account owner, String tagline) throws Exception {
        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"" + tagline + "\"}"))
                .andExpect(status().isOk());
    }

    private URI publicUrl(String nickName) {
        return URI.create("/instructors/" + URLEncoder.encode(nickName, StandardCharsets.UTF_8)
                .replace("+", "%20"));
    }

    /* ════════════════ C — 생성 규칙 ════════════════ */

    @Test
    @DisplayName("C1: 프로필이 없는 계정이 GET 하면 exists=false 이고, 조회만으로는 행이 생기지 않는다")
    void get_doesNotCreate() throws Exception {
        Account me = account("c1@test.com", "diverC1", Role.STUDENT);

        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                // 만들지도 않은 프로필이 isPublished:false 로 내려가면 "비공개로 존재한다"처럼 읽히고,
                // records:null 은 FE 가 배열로 다루다 터진다 — 둘 다 키 자체가 없어야 한다.
                .andExpect(jsonPath("$.isPublished").doesNotExist())
                .andExpect(jsonPath("$.records").doesNotExist());

        assertThat(brandingRepo.findByAccountId(me.getId())).isEmpty();
    }

    @Test
    @DisplayName("C2: 첫 PATCH 가 프로필을 생성한다 (별도 생성 엔드포인트 없음) — 생성 시 발행 상태")
    void firstPatch_createsProfile() throws Exception {
        Account me = account("c2@test.com", "diverC2", Role.STUDENT);

        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"12년차 프리다이버\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.isPublished").value(true))
                .andExpect(jsonPath("$.tagline").value("12년차 프리다이버"));

        assertThat(brandingRepo.findByAccountId(me.getId())).isPresent();
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 발행된 프로필은 비로그인으로 닉네임으로 열린다")
    void publicProfile_isOpenToAnonymous() throws Exception {
        Account owner = account("s1@test.com", "diverS1", Role.STUDENT);
        createPublishedBranding(owner, "바다를 좋아합니다");

        mockMvc.perform(get(publicUrl("diverS1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("diverS1"))
                .andExpect(jsonPath("$.tagline").value("바다를 좋아합니다"))
                .andExpect(jsonPath("$.records").isArray());
    }

    @Test
    @DisplayName("S2: 부분 수정은 보낸 키만 반영하고, 명시적 null 은 그 값을 비운다")
    void patch_appliesOnlySentKeys() throws Exception {
        Account me = account("s2@test.com", "diverS2", Role.STUDENT);
        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"태그라인\",\"bio\":\"자기소개\"}"))
                .andExpect(status().isOk());

        // bio 키를 아예 안 보냄 → 유지, tagline 은 명시적 null → 비움
        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").doesNotExist())
                .andExpect(jsonPath("$.bio").value("자기소개"));
    }

    @Test
    @DisplayName("S3: 발행을 끄면 공개 조회가 막힌다 (승인 게이트 없이 누구나 토글 가능)")
    void unpublish_hidesPublicProfile() throws Exception {
        Account owner = account("s3@test.com", "diverS3", Role.STUDENT);
        createPublishedBranding(owner, "공개중");

        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(false));

        mockMvc.perform(get(publicUrl("diverS3")))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ I — 강사 · 일반 분기 (D2) ════════════════ */

    @Test
    @DisplayName("I1: 승인된 강사는 isInstructor=true 와 자격 뱃지·종목이 함께 내려온다")
    void instructor_getsCertsAndDisciplines() throws Exception {
        Account owner = account("i1@test.com", "diverI1", Role.INSTRUCTOR);
        approveAsInstructor(owner, "FREEDIVING", "AIDA");
        createPublishedBranding(owner, "프리다이빙 강사");

        mockMvc.perform(get(publicUrl("diverI1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isInstructor").value(true))
                .andExpect(jsonPath("$.disciplineCodes[0]").value("FREEDIVING"))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"));
    }

    @Test
    @DisplayName("I2: 일반 유저 응답에는 강사 전용 키(certs·disciplineCodes)가 아예 없다")
    void normalUser_omitsInstructorOnlyKeys() throws Exception {
        Account owner = account("i2@test.com", "diverI2", Role.STUDENT);
        createPublishedBranding(owner, "이제 막 시작했어요");

        mockMvc.perform(get(publicUrl("diverI2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isInstructor").value(false))
                .andExpect(jsonPath("$.certs").doesNotExist())
                .andExpect(jsonPath("$.disciplineCodes").doesNotExist());
    }

    @Test
    @DisplayName("I3: 강사 신청 이력이 없으면 오너 응답에 검수 상태 키가 없다 (일반 유저에겐 검수 개념이 없다)")
    void normalUser_hasNoReviewStatus() throws Exception {
        Account me = account("i3@test.com", "diverI3", Role.STUDENT);
        createPublishedBranding(me, "일반 유저");

        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").doesNotExist())
                .andExpect(jsonPath("$.approvedAt").doesNotExist());
    }

    @Test
    @DisplayName("I4: 승인된 강사의 오너 응답에는 reviewStatus=APPROVED 와 승인 시각이 실린다 (웹 검수 배너용)")
    void instructor_getsReviewStatusAndApprovedAt() throws Exception {
        Account me = account("i4@test.com", "diverI4", Role.INSTRUCTOR);
        approveAsInstructor(me, "SCUBA", "PADI");
        createPublishedBranding(me, "스쿠버 강사");

        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").exists());
    }

    /* ════════════════ E — 닉네임 URL 인코딩 ════════════════ */

    @Test
    @DisplayName("E1: 한글 닉네임도 percent-encoding 으로 정상 조회된다 (BE 는 추가 디코딩을 하지 않는다)")
    void koreanNickName_resolves() throws Exception {
        Account owner = account("e1@test.com", "김다이버", Role.STUDENT);
        createPublishedBranding(owner, "한글 닉네임");

        mockMvc.perform(get(publicUrl("김다이버")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("김다이버"));
    }

    @Test
    @DisplayName("E2: 공백·마침표·+ 가 섞인 기존 닉네임도 조회된다 (소급 변경이 없어 DB 에 남아 있다)")
    void nickNameWithSpecialChars_resolves() throws Exception {
        Account owner = account("e2@test.com", "diver v2.0+pro", Role.STUDENT);
        createPublishedBranding(owner, "특수문자 닉네임");

        mockMvc.perform(get(publicUrl("diver v2.0+pro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("diver v2.0+pro"));
    }

    @Test
    @DisplayName("E3: 닉네임에 '/' 가 있으면 공개 프로필을 열 수 없다 — Spring Security 방화벽이 %2F 를 거부한다")
    void nickNameWithSlash_isRejectedByFirewall() throws Exception {
        Account owner = account("e3@test.com", "diver/pro", Role.STUDENT);
        createPublishedBranding(owner, "슬래시 닉네임");

        // StrictHttpFirewall 이 인코딩된 슬래시를 담은 요청을 아예 거부한다(path traversal 방어).
        // 컨트롤러까지 도달하지 못하므로 상태코드가 아니라 예외로 관측된다.
        assertThatThrownBy(() -> mockMvc.perform(get(publicUrl("diver/pro"))))
                .isInstanceOf(RequestRejectedException.class);
    }

    /* ════════════════ V — 검증 ════════════════ */

    @Test
    @DisplayName("V1: 없는 닉네임은 400 (존재 숨김 — 이 레포는 404 를 쓰지 않는다)")
    void unknownNickName_returns400() throws Exception {
        mockMvc.perform(get(publicUrl("존재하지않는닉"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2: 한 줄 소개가 60자를 넘으면 400 이고 사용자 문구가 msg 로 내려온다")
    void taglineTooLong_returns400() throws Exception {
        Account me = account("v2@test.com", "diverV2", Role.STUDENT);

        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"" + "가".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("한 줄 소개는 60자까지 쓸 수 있어요."));

        assertThat(brandingRepo.findByAccountId(me.getId())).isEmpty(); // 검증 실패면 생성도 안 된다
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 비로그인으로 내 프로필을 열면 401")
    void myBranding_requiresAuth() throws Exception {
        mockMvc.perform(get("/branding/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R2: 강사가 아니어도 편집·발행이 된다 (일반 유저에게도 열린 기능)")
    void nonInstructor_canEditAndPublish() throws Exception {
        Account me = account("r2@test.com", "diverR2", Role.STUDENT);

        mockMvc.perform(patch("/branding/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"일반 유저도 씁니다\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/branding/me/publish")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true));
    }

    @Test
    @DisplayName("R3: 기존 공개 강사 목록(/instructors/public)이 브랜딩 경로에 가려지지 않는다")
    void publicInstructorList_stillRoutes() throws Exception {
        mockMvc.perform(get("/instructors/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").exists());
    }
}
