package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.branding.BrandingPostJpaRepo;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.CertificateVerification;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.community.CommunityCommentJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자격 뱃지 표시 규칙(#330) — <b>사람 표면</b>(마이페이지 · 공개 프로필 · 커뮤니티 작성자 칩)의 실행 가능한 사양.
 * 실 H2 + 실 시큐리티 체인. 자격증은 repo 로 직접 심는다(검증 대상이 뱃지 파생이지 등록 플로우가 아니다).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양.
 * B* = 규칙(자기신고 수강생 레벨 · VERIFIED 강사 레벨만 · 그룹별 최고 1장 · 레벨 내림차순 · verified 는 상태에서),
 * P* = 프로필 표면(마이페이지 · 공개 프로필 · /branding/me), C* = 커뮤니티 작성자 칩(topCert).
 *
 * <p>정책 한 줄: <i>수강생 레벨(LEVEL_1~4)은 자기신고 그대로, 강사 레벨(INSTRUCTOR·INSTRUCTOR_TRAINER)은 VERIFIED 만.
 * 그중 가장 높은 것.</i> 강사 자격 표면(강의 상세 인셋 · 강사 browse)은 <b>이 규칙을 쓰지 않는다</b> —
 * 그쪽은 {@code CourseDetailUseCaseTest I3-1} · {@code InstructorBrowseUseCaseTest S10} 이 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CertificateBadgeUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired StudentCertificateJpaRepo certificateRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired BrandingPostJpaRepo postRepo;
    @Autowired CommunityCommentJpaRepo commentRepo;
    @Autowired NotificationOutboxJpaRepo outboxRepo;

    @AfterEach
    void cleanUp() {
        outboxRepo.deleteAll();
        commentRepo.deleteAll();
        postRepo.deleteAll();
        brandingRepo.deleteAll();
        certificateRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick, Role... roles) {
        return accountRepo.save(Account.builder()
                .email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(roles))).isDeleted(false).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    /** 승인된 강사 신청 — isInstructor 의 출처. 뱃지와는 독립이다(뱃지는 자격증 행에서만 파생). */
    private void approved(Account account, String disciplineCode) {
        applicationRepo.save(InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode).status(InstructorApplicationStatus.APPROVED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .reviewedAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /** 자격증 1장 — 레벨·검증 상태를 골라 심는다. */
    private StudentCertificate cert(Account owner, String disciplineCode, String organizationCode,
                                    CertLevel level, CertificateVerificationStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CertificateVerification verification = status == CertificateVerificationStatus.NONE
                ? CertificateVerification.none()
                : new CertificateVerification(status, CertificateVerificationKind.APPLICATION, null, now, now);
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(organizationCode)
                .organizationName(organizationCode).level(level)
                .certificateNumber("N-" + level).acquiredAt(LocalDate.of(2024, 1, 1))
                .source(CertificateSource.EXTERNAL).photoFileKey("studentCertificate/" + owner.getId() + "/x.jpg")
                .createdAt(now).verification(verification)
                .build());
    }

    private StudentCertificate selfReported(Account owner, String org, CertLevel level) {
        return cert(owner, "FREEDIVING", org, level, CertificateVerificationStatus.NONE);
    }

    private URI publicUrl(String nickName) {
        return URI.create("/instructors/" + URLEncoder.encode(nickName, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private long createPost(Account author, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/community/posts")
                        .header(HttpHeaders.AUTHORIZATION, token(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"QNA\",\"title\":\"" + title + "\",\"body\":\"본문\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /* ═══════════ B — 규칙 ═══════════ */

    @Test
    @DisplayName("B1 수강생이 자기신고한 LEVEL_2 는 검증 없이 그대로 뱃지가 된다 — level=LEVEL_2, verified=false")
    void b1_selfReportedStudentLevel_isShown() throws Exception {
        Account stu = account("b1@pd.com", "학생B1", Role.STUDENT);
        selfReported(stu, "AIDA", CertLevel.LEVEL_2);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].disciplineCode").value("FREEDIVING"))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.certs[0].level").value("LEVEL_2"))
                .andExpect(jsonPath("$.certs[0].verified").value(false));
    }

    @Test
    @DisplayName("B2 강사 레벨은 VERIFIED 만 — NONE·PENDING·REJECTED 인 INSTRUCTOR 자격증은 뱃지가 되지 않는다")
    void b2_instructorLevel_onlyWhenVerified() throws Exception {
        Account me = account("b2@pd.com", "학생B2", Role.STUDENT);
        cert(me, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.NONE);
        cert(me, "FREEDIVING", "PADI", CertLevel.INSTRUCTOR, CertificateVerificationStatus.PENDING);
        cert(me, "FREEDIVING", "SSI", CertLevel.INSTRUCTOR_TRAINER, CertificateVerificationStatus.REJECTED);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(0)));
    }

    @Test
    @DisplayName("B3 VERIFIED 강사 자격증은 verified=true 로 온다 — 값은 레벨이 아니라 검증 상태에서 읽는다")
    void b3_verifiedInstructor_hasVerifiedTrue() throws Exception {
        Account ins = account("b3@pd.com", "강사B3", Role.STUDENT, Role.INSTRUCTOR);
        approved(ins, "FREEDIVING");
        cert(ins, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.VERIFIED);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].level").value("INSTRUCTOR"))
                .andExpect(jsonPath("$.certs[0].verified").value(true));
    }

    @Test
    @DisplayName("B4 같은 (종목,단체)는 가장 높은 레벨 1장만 — AIDA LEVEL_1 + LEVEL_3 이면 LEVEL_3 하나")
    void b4_topPerDisciplineOrganization() throws Exception {
        Account stu = account("b4@pd.com", "학생B4", Role.STUDENT);
        selfReported(stu, "AIDA", CertLevel.LEVEL_1);
        selfReported(stu, "AIDA", CertLevel.LEVEL_3);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.certs[0].level").value("LEVEL_3"));
    }

    @Test
    @DisplayName("B5 단체가 다르면 각각 1장 — 크로스오버(AIDA VERIFIED 강사 + SSI LEVEL_2 자기신고)는 둘 다, 레벨 내림차순")
    void b5_crossover_allGroups_sortedByLevelDesc() throws Exception {
        Account ins = account("b5@pd.com", "강사B5", Role.STUDENT, Role.INSTRUCTOR);
        approved(ins, "FREEDIVING");
        selfReported(ins, "SSI", CertLevel.LEVEL_2);          // 먼저 심어도
        cert(ins, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.VERIFIED);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(2)))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"))   // 높은 레벨이 [0]
                .andExpect(jsonPath("$.certs[0].level").value("INSTRUCTOR"))
                .andExpect(jsonPath("$.certs[0].verified").value(true))
                .andExpect(jsonPath("$.certs[1].organizationCode").value("SSI"))
                .andExpect(jsonPath("$.certs[1].level").value("LEVEL_2"))
                .andExpect(jsonPath("$.certs[1].verified").value(false));
    }

    @Test
    @DisplayName("B6 심사 중인 강사 지망자 — INSTRUCTOR 는 PENDING 이라 빠지지만 같은 단체의 자기신고 LEVEL_4 는 그대로 보인다 (뱃지가 사라지는 구간이 없다)")
    void b6_pendingApplicant_keepsStudentBadge() throws Exception {
        Account stu = account("b6@pd.com", "지망B6", Role.STUDENT);
        selfReported(stu, "AIDA", CertLevel.LEVEL_4);
        cert(stu, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.PENDING);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].level").value("LEVEL_4"))
                .andExpect(jsonPath("$.certs[0].verified").value(false));
    }

    /* ═══════════ P — 프로필 표면 ═══════════ */

    @Test
    @DisplayName("P1 공개 프로필(GET /instructors/{nickName})도 같은 규칙 — 일반 유저의 자기신고 뱃지가 보이고 isInstructor 는 여전히 false")
    void p1_publicProfile_showsStudentBadge() throws Exception {
        Account stu = account("p1@pd.com", "학생P1", Role.STUDENT);
        selfReported(stu, "AIDA", CertLevel.LEVEL_2);

        mockMvc.perform(get(publicUrl("학생P1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isInstructor").value(false))
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].level").value("LEVEL_2"))
                .andExpect(jsonPath("$.certs[0].verified").value(false))
                // 강사 전용 키는 여전히 없다 — certs 가 강사 판정이 아니다.
                .andExpect(jsonPath("$.disciplineCodes").doesNotExist());
    }

    @Test
    @DisplayName("P2 자격증이 없으면 certs 는 빈 배열(키 생략 아님) — 마이페이지·공개 프로필·/branding/me 모두")
    void p2_noCertificates_emptyArrayEverywhere() throws Exception {
        Account stu = account("p2@pd.com", "학생P2", Role.STUDENT);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.certs", hasSize(0)));
        mockMvc.perform(get(publicUrl("학생P2")))
                .andExpect(jsonPath("$.certs", hasSize(0)));
        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(jsonPath("$.certs", hasSize(0)));
    }

    @Test
    @DisplayName("P3 GET /branding/me 는 공개 프로필과 같은 값 — 프로필 미작성이어도 뱃지는 온다")
    void p3_myBranding_sameBadges() throws Exception {
        Account stu = account("p3@pd.com", "학생P3", Role.STUDENT);
        selfReported(stu, "PADI", CertLevel.LEVEL_1);
        selfReported(stu, "AIDA", CertLevel.LEVEL_3);

        mockMvc.perform(get("/branding/me").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.certs", hasSize(2)))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.certs[0].level").value("LEVEL_3"))
                .andExpect(jsonPath("$.certs[1].organizationCode").value("PADI"))
                .andExpect(jsonPath("$.certs[1].level").value("LEVEL_1"));
    }

    /* ═══════════ C — 커뮤니티 작성자 칩 ═══════════ */

    @Test
    @DisplayName("C1 피드 작성자에 topCert 최고 1장 — 수강생도 자기 AIDA2 칩을 달고, isInstructor 와는 독립이다")
    void c1_feedAuthor_topCert() throws Exception {
        Account stu = account("c1@pd.com", "학생C1", Role.STUDENT);
        selfReported(stu, "PADI", CertLevel.LEVEL_1);
        selfReported(stu, "AIDA", CertLevel.LEVEL_2);
        createPost(stu, "첫 글");

        mockMvc.perform(get("/community/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.posts[0].author.isInstructor").value(false))
                .andExpect(jsonPath("$._embedded.posts[0].author.topCert.organizationCode").value("AIDA"))
                .andExpect(jsonPath("$._embedded.posts[0].author.topCert.level").value("LEVEL_2"))
                .andExpect(jsonPath("$._embedded.posts[0].author.topCert.verified").value(false));
    }

    @Test
    @DisplayName("C2 표시할 자격이 없으면 topCert 키 자체가 없다 — 미검증 INSTRUCTOR 만 있어도 마찬가지")
    void c2_noDisplayableCert_keyOmitted() throws Exception {
        Account stu = account("c2@pd.com", "학생C2", Role.STUDENT);
        cert(stu, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.PENDING);
        long id = createPost(stu, "자격 없는 글");

        mockMvc.perform(get("/community/posts"))
                .andExpect(jsonPath("$._embedded.posts[0].author.topCert").doesNotExist());
        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.author.topCert").doesNotExist());
    }

    @Test
    @DisplayName("C3 상세·댓글 작성자도 같은 합성 — VERIFIED 강사는 상세에서 INSTRUCTOR/verified=true, 댓글 단 수강생은 자기 LEVEL_3")
    void c3_detailAndComment_sameComposition() throws Exception {
        Account ins = account("c3i@pd.com", "강사C3", Role.STUDENT, Role.INSTRUCTOR);
        approved(ins, "FREEDIVING");
        selfReported(ins, "AIDA", CertLevel.LEVEL_4);          // 같은 단체의 낮은 레벨은 접힌다
        cert(ins, "FREEDIVING", "AIDA", CertLevel.INSTRUCTOR, CertificateVerificationStatus.VERIFIED);
        Account stu = account("c3s@pd.com", "학생C3", Role.STUDENT);
        selfReported(stu, "SSI", CertLevel.LEVEL_3);

        long id = createPost(ins, "강사 글");
        mockMvc.perform(post("/community/posts/" + id + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"댓글\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/community/posts/" + id))
                .andExpect(jsonPath("$.author.isInstructor").value(true))
                .andExpect(jsonPath("$.author.topCert.organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.author.topCert.level").value("INSTRUCTOR"))
                .andExpect(jsonPath("$.author.topCert.verified").value(true));
        mockMvc.perform(get("/community/posts/" + id + "/comments"))
                .andExpect(jsonPath("$._embedded.comments[0].author.isInstructor").value(false))
                .andExpect(jsonPath("$._embedded.comments[0].author.topCert.organizationCode").value("SSI"))
                .andExpect(jsonPath("$._embedded.comments[0].author.topCert.level").value("LEVEL_3"))
                .andExpect(jsonPath("$._embedded.comments[0].author.topCert.verified").value(false));
    }
}
