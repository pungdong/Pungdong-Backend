package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.certificate.CertificateReview;
import com.diving.pungdong.certificate.CertificateReviewJpaRepo;
import com.diving.pungdong.certificate.CertificateReviewKind;
import com.diving.pungdong.certificate.CertificateReviewStatus;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.CertificateVerification;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.StubIdentityVerifier;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드민 검수 큐 use-case — {@code /admin/certificate-reviews/**}. 강사 신청(NEW)·추가 자격증(ADDITIONAL)·
 * 재검수(RE_VERIFY)가 한 큐에 섞여 reviewId 로 승인/반려된다.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. Q* 큐(목록·건수·상세) / P* 처리(승인·반려) / R* 권한.
 * 실 H2 + 실 시큐리티 + 실 서비스. 신청은 HTTP 로 제출하고(Rule B 가 NEW 행을 만든다), 추가·재검수 자격증은
 * {@code /certificates} HTTP 쓰기로 Rule A 가 행을 만들게 한다 — 큐에 들어오는 경로 자체를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CertificateReviewUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired StudentCertificateJpaRepo certificateRepo;
    @Autowired CertificateReviewJpaRepo reviewRepo;
    @Autowired IdentityVerificationJpaRepo identityVerificationRepo;

    @AfterEach
    void cleanUp() {
        reviewRepo.deleteAll();
        applicationRepo.deleteAll();
        certificateRepo.deleteAll();
        identityVerificationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ─── fixtures ─────────────────────────────────────────── */

    private Account account(String email, String nick, Role role) {
        return accountRepo.save(Account.builder().email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StudentCertificate instructorCert(Account owner, String disciplineCode, String org) {
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(org).organizationName(org)
                .level(CertLevel.INSTRUCTOR).certificateNumber(org + "-1").acquiredAt(LocalDate.of(2021, 3, 1))
                .source(CertificateSource.EXTERNAL)
                .photoFileKey("studentCertificate/" + owner.getId() + "/" + org.toLowerCase() + ".jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private StudentCertificate verifiedCert(Account owner, String disciplineCode, String org) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(org).organizationName(org)
                .level(CertLevel.INSTRUCTOR).certificateNumber(org + "-V").acquiredAt(LocalDate.of(2020, 1, 1))
                .source(CertificateSource.EXTERNAL)
                .photoFileKey("studentCertificate/" + owner.getId() + "/" + org.toLowerCase() + ".jpg")
                .createdAt(now)
                .verification(new CertificateVerification(CertificateVerificationStatus.VERIFIED,
                        CertificateVerificationKind.APPLICATION, null, now, now)).build());
    }

    private InstructorApplication approvedInstructor(Account account, String disciplineCode) {
        return applicationRepo.save(InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode).status(InstructorApplicationStatus.APPROVED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).reviewedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private StudentCertificate reload(StudentCertificate c) {
        return certificateRepo.findById(c.getId()).orElseThrow();
    }

    /** 본인확인(stub) → 신청 제출(HTTP) → applicationId. Rule B 가 NEW 검수 행을 만든다. */
    private long submitApplication(Account applicant, String disciplineCode, List<Long> certificateIds) throws Exception {
        String t = token(applicant);
        Map<String, Object> idBody = new HashMap<>();
        idBody.put("realName", "김다이버"); idBody.put("birth", "19980914"); idBody.put("gender", "MALE");
        idBody.put("phoneNumber", "010-1234-5678"); idBody.put("carrier", "SKT"); idBody.put("method", "SMS");
        idBody.put("agreedRequiredTerms", true);
        MvcResult idRes = mockMvc.perform(post("/identity-verifications").header(HttpHeaders.AUTHORIZATION, t)
                        .contentType(MediaType.APPLICATION_JSON).content(write(idBody)))
                .andExpect(status().isCreated()).andReturn();
        long vid = objectMapper.readTree(idRes.getResponse().getContentAsString()).get("verificationId").asLong();
        mockMvc.perform(post("/identity-verifications/" + vid + "/confirm").header(HttpHeaders.AUTHORIZATION, t)
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("otp", StubIdentityVerifier.MAGIC_OTP))))
                .andExpect(status().isOk());
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", disciplineCode);
        body.put("verificationId", vid);
        body.put("certificateIds", certificateIds);
        MvcResult res = mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, t)
                        .contentType(MediaType.APPLICATION_JSON).content(write(body)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("applicationId").asLong();
    }

    /** 승인된 강사가 내 자격증에 강사레벨 1장 등록(HTTP) → Rule A → PENDING(ADDITIONAL). 자격증 id 반환. */
    private long registerAdditional(Account instructor, String disciplineCode, String org) throws Exception {
        String t = token(instructor);
        MvcResult up = mockMvc.perform(multipart("/certificates/photos")
                        .file(new org.springframework.mock.web.MockMultipartFile("image", "c.jpg", MediaType.IMAGE_JPEG_VALUE, "b".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, t)).andExpect(status().isOk()).andReturn();
        String fileKey = objectMapper.readTree(up.getResponse().getContentAsString()).get("fileKey").asText();
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", disciplineCode); body.put("organizationCode", org); body.put("organizationName", org);
        body.put("level", "INSTRUCTOR"); body.put("certificateNumber", org + "-ADD"); body.put("acquiredAt", "2024-01-10");
        body.put("photoFileKey", fileKey);
        MvcResult res = mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, t)
                        .contentType(MediaType.APPLICATION_JSON).content(write(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verification.kind").value("ADDITIONAL")).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    /** VERIFIED 자격증의 번호를 고쳐(HTTP) → Rule A → PENDING(RE_VERIFY). */
    private void editNumber(Account owner, StudentCertificate cert, String newNumber) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", cert.getDisciplineCode()); body.put("organizationCode", cert.getOrganizationCode());
        body.put("organizationName", cert.getOrganizationCode()); body.put("level", "INSTRUCTOR");
        body.put("certificateNumber", newNumber); body.put("acquiredAt", "2020-01-01");
        mockMvc.perform(put("/certificates/" + cert.getId()).header(HttpHeaders.AUTHORIZATION, token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(write(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification.kind").value("RE_VERIFY"));
    }

    private long pendingReviewIdOf(Long certificateId) {
        return reviewRepo.findFirstByCertificateIdAndStatus(certificateId, CertificateReviewStatus.PENDING).orElseThrow().getId();
    }

    private JsonNode list(Account admin, String query) throws Exception {
        MvcResult res = mockMvc.perform(get("/admin/certificate-reviews" + query).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    /* ════════════════ Q — 큐 ════════════════ */

    @Test
    @DisplayName("Q1: 강사 신청(NEW)·추가 자격증(ADDITIONAL)·재검수(RE_VERIFY)가 한 목록에 kind 로 구분돼 최신순으로 나오고, 단체 칩·대상 id 가 종류별로 채워진다")
    void list_mergesThreeKinds() throws Exception {
        Account admin = account("adm-q1@t.com", "admQ1", Role.ADMIN);
        Account applicant = account("q1a@t.com", "diverQ1a", Role.STUDENT);
        submitApplication(applicant, "FREEDIVING", List.of(instructorCert(applicant, "FREEDIVING", "AIDA").getId(),
                instructorCert(applicant, "FREEDIVING", "PADI").getId()));
        Account instructor = account("q1b@t.com", "diverQ1b", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        StudentCertificate verified = verifiedCert(instructor, "SCUBA", "SSI");
        verifiedCert(instructor, "SCUBA", "NAUI");
        long additionalId = registerAdditional(instructor, "SCUBA", "PADI");
        editNumber(instructor, verified, "SSI-NEW");

        JsonNode page = list(admin, "?status=PENDING");
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(3);
        JsonNode rows = page.get("_embedded").get("reviews");
        // 최신순: RE_VERIFY → ADDITIONAL → NEW
        assertThat(rows.get(0).get("kind").asText()).isEqualTo("RE_VERIFY");
        assertThat(rows.get(0).get("certificateId").asLong()).isEqualTo(verified.getId());
        assertThat(rows.get(0).get("applicationId").isNull()).isTrue();
        assertThat(rows.get(0).get("organizationCodes").get(0).asText()).isEqualTo("SSI");
        assertThat(rows.get(1).get("kind").asText()).isEqualTo("ADDITIONAL");
        assertThat(rows.get(1).get("certificateId").asLong()).isEqualTo(additionalId);
        assertThat(rows.get(2).get("kind").asText()).isEqualTo("NEW");
        assertThat(rows.get(2).get("applicationId").isNull()).isFalse();
        assertThat(rows.get(2).get("nickName").asText()).isEqualTo("diverQ1a");
        assertThat(rows.get(2).get("email").asText()).isEqualTo("q1a@t.com");
        assertThat(rows.get(2).get("organizationCodes")).hasSize(2);
        assertThat(rows.get(2).get("verifiedCertificateMissing").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Q2: counts 는 세 종류를 합친 상태별 건수 — pending/approved/rejected/total")
    void counts_acrossKinds() throws Exception {
        Account admin = account("adm-q2@t.com", "admQ2", Role.ADMIN);
        Account applicant = account("q2a@t.com", "diverQ2a", Role.STUDENT);
        long appId = submitApplication(applicant, "FREEDIVING", List.of(instructorCert(applicant, "FREEDIVING", "AIDA").getId()));
        Account instructor = account("q2b@t.com", "diverQ2b", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        verifiedCert(instructor, "SCUBA", "SSI");
        long add1 = registerAdditional(instructor, "SCUBA", "PADI");
        registerAdditional(instructor, "SCUBA", "NAUI");
        // NEW 승인, ADDITIONAL 하나 반려
        long newReviewId = reviewRepo.findFirstByApplicationIdAndStatus(appId, CertificateReviewStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(post("/admin/certificate-reviews/" + newReviewId + "/approve").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/certificate-reviews/" + pendingReviewIdOf(add1) + "/reject").header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("reason", "사진 흐림"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/certificate-reviews/counts").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.total").value(3));
        // status 생략 = 전체(이력 포함)
        assertThat(list(admin, "").get("page").get("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("Q3: NEW 상세엔 신청 블록(본인확인 PII·첨부 id)과 첨부 자격증 풀 필드가, RE_VERIFY 상세엔 previous(최초 VERIFIED 값)가 붙는다")
    void detail_perKind() throws Exception {
        Account admin = account("adm-q3@t.com", "admQ3", Role.ADMIN);
        Account applicant = account("q3a@t.com", "diverQ3a", Role.STUDENT);
        StudentCertificate attached = instructorCert(applicant, "FREEDIVING", "AIDA");
        long appId = submitApplication(applicant, "FREEDIVING", List.of(attached.getId()));
        long newReviewId = reviewRepo.findFirstByApplicationIdAndStatus(appId, CertificateReviewStatus.PENDING).orElseThrow().getId();

        mockMvc.perform(get("/admin/certificate-reviews/" + newReviewId).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NEW"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.application.applicationId").value(appId))
                .andExpect(jsonPath("$.application.realName").value("김다이버"))
                .andExpect(jsonPath("$.application.certificateIds[0]").value(attached.getId()))
                .andExpect(jsonPath("$.certificates[0].certificateId").value(attached.getId()))
                .andExpect(jsonPath("$.certificates[0].verification.status").value("PENDING"))
                .andExpect(jsonPath("$.previous").doesNotExist());

        Account instructor = account("q3b@t.com", "diverQ3b", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        StudentCertificate verified = verifiedCert(instructor, "SCUBA", "SSI");
        verifiedCert(instructor, "SCUBA", "NAUI");
        editNumber(instructor, verified, "SSI-NEW");

        mockMvc.perform(get("/admin/certificate-reviews/" + pendingReviewIdOf(verified.getId())).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RE_VERIFY"))
                .andExpect(jsonPath("$.application").doesNotExist())
                .andExpect(jsonPath("$.certificates[0].certificateNumber").value("SSI-NEW"))
                .andExpect(jsonPath("$.previous.certificateNumber").value("SSI-V"))
                .andExpect(jsonPath("$.previous.organizationCode").value("SSI"))
                .andExpect(jsonPath("$.previous.level").value("INSTRUCTOR"));
    }

    /* ════════════════ P — 처리 ════════════════ */

    @Test
    @DisplayName("P1: NEW 행을 reviewId 로 승인하면 강사 신청 승인과 같다 — INSTRUCTOR 부여 + 첨부 VERIFIED + 행 APPROVED(reviewer 기록)")
    void approveNew_delegatesToApplication() throws Exception {
        Account admin = account("adm-p1@t.com", "admP1", Role.ADMIN);
        Account applicant = account("p1@t.com", "diverP1", Role.STUDENT);
        StudentCertificate attached = instructorCert(applicant, "FREEDIVING", "AIDA");
        long appId = submitApplication(applicant, "FREEDIVING", List.of(attached.getId()));
        long reviewId = reviewRepo.findFirstByApplicationIdAndStatus(appId, CertificateReviewStatus.PENDING).orElseThrow().getId();

        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/approve").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());

        assertThat(accountRepo.findById(applicant.getId()).orElseThrow().getRoles()).contains(Role.INSTRUCTOR);
        assertThat(applicationRepo.findById(appId).orElseThrow().getStatus()).isEqualTo(InstructorApplicationStatus.APPROVED);
        assertThat(reload(attached).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.VERIFIED);
        CertificateReview review = reviewRepo.findById(reviewId).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(CertificateReviewStatus.APPROVED);
        assertThat(review.getReviewerId()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("P2: ADDITIONAL 행을 승인하면 그 자격증만 VERIFIED 가 되고 공개 인증마크(브랜딩 certs)에 새 단체가 추가된다")
    void approveAdditional_verifiesCertificateAndBadge() throws Exception {
        Account admin = account("adm-p2@t.com", "admP2", Role.ADMIN);
        Account instructor = account("p2@t.com", "diverP2", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        verifiedCert(instructor, "SCUBA", "SSI");
        long addId = registerAdditional(instructor, "SCUBA", "PADI");

        mockMvc.perform(post("/admin/certificate-reviews/" + pendingReviewIdOf(addId) + "/approve").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());

        StudentCertificate verified = certificateRepo.findById(addId).orElseThrow();
        assertThat(verified.getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.VERIFIED);
        assertThat(verified.getVerification().getKind()).isEqualTo(CertificateVerificationKind.ADDITIONAL);
        assertThat(verified.getVerification().getReviewedAt()).isNotNull();
        // 내 프로필 뱃지에 PADI 가 보인다 (VERIFIED 가 곧 마크)
        MvcResult res = mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(status().isOk()).andReturn();
        JsonNode certs = objectMapper.readTree(res.getResponse().getContentAsString()).get("certs");
        assertThat(certs).hasSize(2);
        assertThat(certs.findValuesAsText("organizationCode")).containsExactlyInAnyOrder("SSI", "PADI");
    }

    @Test
    @DisplayName("P3: RE_VERIFY 행을 반려하면 자격증은 REJECTED + 사유이고, 그게 마지막 검증 자격증이었으면 목록에 verifiedCertificateMissing=true 가 뜬다 (권한은 유지 — 인정한 구멍)")
    void rejectReVerify_flagsMissingVerified() throws Exception {
        Account admin = account("adm-p3@t.com", "admP3", Role.ADMIN);
        Account instructor = account("p3@t.com", "diverP3", Role.INSTRUCTOR);
        approvedInstructor(instructor, "FREEDIVING");
        StudentCertificate only = verifiedCert(instructor, "FREEDIVING", "AIDA");
        editNumber(instructor, only, "AIDA-TYPO");
        long reviewId = pendingReviewIdOf(only.getId());

        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/reject").header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("reason", "번호가 카드와 달라요"))))
                .andExpect(status().isOk());

        StudentCertificate rejected = reload(only);
        assertThat(rejected.getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.REJECTED);
        assertThat(rejected.getVerification().getReason()).isEqualTo("번호가 카드와 달라요");
        assertThat(accountRepo.findById(instructor.getId()).orElseThrow().getRoles()).contains(Role.INSTRUCTOR);

        JsonNode rows = list(admin, "?status=REJECTED").get("_embedded").get("reviews");
        assertThat(rows.get(0).get("reviewId").asLong()).isEqualTo(reviewId);
        assertThat(rows.get(0).get("verifiedCertificateMissing").asBoolean()).isTrue();
        mockMvc.perform(get("/admin/certificate-reviews/" + reviewId).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(jsonPath("$.verifiedCertificateMissing").value(true))
                .andExpect(jsonPath("$.reason").value("번호가 카드와 달라요"));
    }

    @Test
    @DisplayName("P4: 이미 처리된 행을 다시 승인/반려하면 400 + msg, 없는 reviewId 는 -1009(존재 숨김, 레포 규약)")
    void approve_nonPending_rejected() throws Exception {
        Account admin = account("adm-p4@t.com", "admP4", Role.ADMIN);
        Account instructor = account("p4@t.com", "diverP4", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        long addId = registerAdditional(instructor, "SCUBA", "PADI");
        long reviewId = pendingReviewIdOf(addId);
        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/approve").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/approve").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("이미 처리된 검수 요청이에요."));
        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/reject").header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("reason", "x"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/certificate-reviews/999999").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
    }

    @Test
    @DisplayName("P5: 반려 사유를 비우면 400 + 어느 필드가 왜 틀렸는지 msg")
    void reject_requiresReason() throws Exception {
        Account admin = account("adm-p5@t.com", "admP5", Role.ADMIN);
        Account instructor = account("p5@t.com", "diverP5", Role.INSTRUCTOR);
        approvedInstructor(instructor, "SCUBA");
        long reviewId = pendingReviewIdOf(registerAdditional(instructor, "SCUBA", "PADI"));

        mockMvc.perform(post("/admin/certificate-reviews/" + reviewId + "/reject").header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("reason", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("반려 사유를 입력해주세요."));
        assertThat(reviewRepo.findById(reviewId).orElseThrow().getStatus()).isEqualTo(CertificateReviewStatus.PENDING);
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 강사·수강생은 검수 큐를 볼 수도 처리할 수도 없다(403), 비로그인은 401")
    void adminOnly() throws Exception {
        Account instructor = account("r1@t.com", "diverR1", Role.INSTRUCTOR);
        mockMvc.perform(get("/admin/certificate-reviews").header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/certificate-reviews/1/approve").header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/certificate-reviews/counts"))
                .andExpect(status().isUnauthorized());
    }
}
