package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.AdminAccountInitializer;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.instructorapplication.ApplicationCertificateJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.instructorapplication.storage.CertificateImageStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 신청 흐름 use-case 시나리오 (본인확인 stub 포함).
 *
 * <p>실제 H2 + Spring Security 필터 체인 + 실제 서비스/JPA 로 "어떤 입력에 어떤 신청 상태가
 * 생기고, 승인 시 권한이 어떻게 바뀌는가" 를 검증한다. 외부 경계인 S3 만 {@code @MockBean}.
 * 본인확인은 {@link com.diving.pungdong.instructorapplication.StubIdentityVerifier} (우리 stub)
 * 를 그대로 사용 — 실 외부 연동이 아니므로 mock 하지 않는다.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 의 한글 시나리오를 위에서 아래로 읽으면 강사 신청
 * 사양이 된다. 그룹 — S* 성공 / V* 검증거절 / D* 중복 / R* 권한 / J* 반려·재제출 / A* 어드민목록.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructorApplicationUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired ApplicationCertificateJpaRepo certificateRepo;
    @Autowired IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired AdminAccountInitializer adminAccountInitializer;

    @MockBean CertificateImageStorage certificateImageStorage;

    @AfterEach
    void cleanUp() {
        certificateRepo.deleteAll();
        applicationRepo.deleteAll();
        identityVerificationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ─── fixtures ─────────────────────────────────────────── */

    private Account createAccount(String email, String nick, Role role) {
        Account account = Account.builder()
                .email(email)
                .password("encoded")
                .nickName(nick)
                .roles(new HashSet<>(Set.of(role)))
                .build();
        return accountRepo.save(account);
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private String identityBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("realName", "김다이버");
        body.put("birth", "19980914");
        body.put("gender", "MALE");
        body.put("phoneNumber", "010-1234-5678");
        body.put("provider", "KAKAO");
        body.put("agreedRequiredTerms", true);
        return write(body);
    }

    private String submitBody(Long verificationId, String orgCode, String orgOther, List<String> urls) {
        Map<String, Object> body = new HashMap<>();
        body.put("verificationId", verificationId);
        body.put("organizationCode", orgCode);
        body.put("organizationOther", orgOther);
        body.put("certificateImageUrls", urls);
        return write(body);
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 본인확인 stub 을 거쳐 verificationId 를 받아온다. */
    private long verifyIdentity(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(true))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("verificationId").asLong();
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 수강생이 본인확인 → 단체/자격증으로 신청 제출하면 201 + 신청 1건 SUBMITTED 로 DB 생성")
    void submit_succeeds() throws Exception {
        Account student = createAccount("s1@test.com", "diver1", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "PADI", null, List.of("https://s3/cert1.png"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        InstructorApplication saved = applicationRepo.findByAccountId(student.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(InstructorApplicationStatus.SUBMITTED);
        assertThat(saved.getOrganizationCode()).isEqualTo("PADI");
        // 자격증/본인확인은 LAZY 연관이라 트랜잭션 밖에서 직접 만지지 않고 별도 조회로 검증
        assertThat(certificateRepo.findAll()).hasSize(1);
        assertThat(certificateRepo.findAll().get(0).getFileURL()).isEqualTo("https://s3/cert1.png");
        assertThat(identityVerificationRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("S2: 신청 이력이 없는 사용자가 내 신청을 조회하면 200 {status:NONE} (404 아님)")
    void getMyApplication_returnsNone_whenNeverApplied() throws Exception {
        Account student = createAccount("s2@test.com", "diver2", Role.STUDENT);

        mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    @DisplayName("S3: 제출 후 내 신청을 조회하면 SUBMITTED + 선택한 단체·자격증·본인인증여부가 보인다")
    void getMyApplication_reflectsSubmission() throws Exception {
        Account student = createAccount("s3@test.com", "diver3", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "AIDA", null, List.of("https://s3/cert1.png", "https://s3/cert2.png"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.identityVerified").value(true))
                .andExpect(jsonPath("$.certificateImageUrls.length()").value(2));
    }

    /* ════════════════ V — 검증 거절 ════════════════ */

    @Test
    @DisplayName("V1: 본인확인(verificationId) 없이 제출하면 400 + DB 에 신청 안 생김")
    void submit_rejectedWithoutVerification() throws Exception {
        Account student = createAccount("v1@test.com", "diver4", Role.STUDENT);
        String token = tokenFor(student);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(null, "PADI", null, List.of("https://s3/cert1.png"))))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findByAccountId(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("V2: 자격증 이미지 0장으로 제출하면 400")
    void submit_rejectedWithoutCertificates() throws Exception {
        Account student = createAccount("v2@test.com", "diver5", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "PADI", null, List.of())))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findByAccountId(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("V3: 단체를 OTHER(기타)로 골랐는데 직접입력값을 비우면 400")
    void submit_rejectedWhenOtherWithoutFreeText() throws Exception {
        Account student = createAccount("v3@test.com", "diver6", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "OTHER", "  ", List.of("https://s3/cert1.png"))))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findByAccountId(student.getId())).isEmpty();
    }

    /* ════════════════ D — 중복 ════════════════ */

    @Test
    @DisplayName("D1: 이미 심사중(SUBMITTED) 신청이 있는데 또 제출하면 400 (중복 신청 불가)")
    void submit_rejectsDuplicateWhilePending() throws Exception {
        Account student = createAccount("d1@test.com", "diver7", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "PADI", null, List.of("https://s3/cert1.png"))))
                .andExpect(status().isCreated());

        long verificationId2 = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId2, "SSI", null, List.of("https://s3/cert9.png"))))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findAll()).hasSize(1);
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 일반 수강생이 어드민 승인 엔드포인트를 호출하면 403")
    void approve_forbiddenForStudent() throws Exception {
        Account student = createAccount("r1@test.com", "diver8", Role.STUDENT);

        mockMvc.perform(post("/admin/instructor-applications/1/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R2: 어드민이 SUBMITTED 신청을 승인하면 200 + 그 계정에 INSTRUCTOR 추가 + isCertified=true + APPROVED")
    void approve_grantsInstructorRole() throws Exception {
        Account student = createAccount("r2@test.com", "diver9", Role.STUDENT);
        Account admin = createAccount("admin2@test.com", "admin2", Role.ADMIN);
        String studentToken = tokenFor(student);
        long verificationId = verifyIdentity(studentToken);
        MvcResult submitted = mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "PADI", null, List.of("https://s3/cert1.png"))))
                .andExpect(status().isCreated())
                .andReturn();
        long applicationId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("applicationId").asLong();

        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk());

        Account approved = accountRepo.findById(student.getId()).orElseThrow();
        assertThat(approved.getRoles()).contains(Role.INSTRUCTOR, Role.STUDENT);
        assertThat(approved.getIsCertified()).isTrue();
        assertThat(applicationRepo.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(InstructorApplicationStatus.APPROVED);
    }

    @Test
    @DisplayName("R3: 승인 직후 그 사용자가 토큰 재발급 없이 강사 전용 API 를 호출하면 통과한다 (DB기반 권한)")
    void approvedUser_passesInstructorEndpoint_withOldToken() throws Exception {
        Account student = createAccount("r3@test.com", "diver10", Role.STUDENT);
        Account admin = createAccount("admin3@test.com", "admin3", Role.ADMIN);
        String oldStudentToken = tokenFor(student); // 승인 전 발급된 토큰
        long verificationId = verifyIdentity(oldStudentToken);
        MvcResult submitted = mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, oldStudentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "PADI", null, List.of("https://s3/cert1.png"))))
                .andExpect(status().isCreated())
                .andReturn();
        long applicationId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("applicationId").asLong();

        // 승인 전에는 강사 전용 API 가 403
        mockMvc.perform(get("/account/instructor/certificate/list")
                        .header(HttpHeaders.AUTHORIZATION, oldStudentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk());

        // 승인 후, 같은(옛) 토큰으로 강사 전용 API 호출 → 통과 (권한이 매 요청 DB 에서 재계산되므로)
        mockMvc.perform(get("/account/instructor/certificate/list")
                        .header(HttpHeaders.AUTHORIZATION, oldStudentToken))
                .andExpect(status().isOk());
    }

    /* ════════════════ J — 반려·재제출 ════════════════ */

    @Test
    @DisplayName("J1: 어드민이 사유를 담아 반려하면 status=REJECTED + 사유가 저장된다")
    void reject_storesReason() throws Exception {
        Account student = createAccount("j1@test.com", "diver11", Role.STUDENT);
        Account admin = createAccount("admin4@test.com", "admin4", Role.ADMIN);
        String studentToken = tokenFor(student);
        long applicationId = submitApplication(studentToken, "PADI");

        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("reason", "자격증 사진이 흐릿합니다"))))
                .andExpect(status().isOk());

        InstructorApplication rejected = applicationRepo.findById(applicationId).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(InstructorApplicationStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("자격증 사진이 흐릿합니다");
    }

    @Test
    @DisplayName("J2: 반려된 신청을 신청자가 PUT 으로 수정·재제출하면 status=SUBMITTED 로 복귀한다")
    void resubmit_afterRejection() throws Exception {
        Account student = createAccount("j2@test.com", "diver12", Role.STUDENT);
        Account admin = createAccount("admin5@test.com", "admin5", Role.ADMIN);
        String studentToken = tokenFor(student);
        long applicationId = submitApplication(studentToken, "PADI");
        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("reason", "재촬영 필요"))))
                .andExpect(status().isOk());

        long newVerification = verifyIdentity(studentToken);
        mockMvc.perform(put("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(newVerification, "SSI", null, List.of("https://s3/cert-new.png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        InstructorApplication resubmitted = applicationRepo.findById(applicationId).orElseThrow();
        assertThat(resubmitted.getStatus()).isEqualTo(InstructorApplicationStatus.SUBMITTED);
        assertThat(resubmitted.getOrganizationCode()).isEqualTo("SSI");
        assertThat(resubmitted.getRejectionReason()).isNull();
    }

    /* ════════════════ A — 어드민 목록 ════════════════ */

    @Test
    @DisplayName("A1: 어드민이 ?status=SUBMITTED 목록을 조회하면 대기중만 나오고 승인된 건은 빠진다")
    void adminList_showsOnlyPending() throws Exception {
        Account pending = createAccount("a1@test.com", "diver13", Role.STUDENT);
        Account approvedUser = createAccount("a2@test.com", "diver14", Role.STUDENT);
        Account admin = createAccount("admin6@test.com", "admin6", Role.ADMIN);
        String adminToken = tokenFor(admin);

        submitApplication(tokenFor(pending), "PADI");
        long approvedAppId = submitApplication(tokenFor(approvedUser), "AIDA");
        mockMvc.perform(post("/admin/instructor-applications/" + approvedAppId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());

        // 대기 목록엔 SUBMITTED 1건만 (승인된 건 제외) — 임베디드 키 이름에 의존하지 않게
        // 페이지 메타로 검증. DB 엔 신청 2건이 존재하지만 목록은 1건.
        mockMvc.perform(get("/admin/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .param("status", "SUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));

        assertThat(applicationRepo.findAll()).hasSize(2);
        assertThat(applicationRepo.findAllByStatus(InstructorApplicationStatus.SUBMITTED,
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    /* ════════════════ U — 업로드 (2-phase 1단계) ════════════════ */

    @Test
    @DisplayName("U1: 자격증 이미지를 업로드하면 S3 URL 을 돌려준다 (2-phase 1단계)")
    void uploadCertificateImage_returnsUrl() throws Exception {
        Account student = createAccount("u1@test.com", "diver15", Role.STUDENT);
        given(certificateImageStorage.store(any(), any())).willReturn("https://s3.fake/instructorCertificate/x.png");

        MockMultipartFile file = new MockMultipartFile(
                "image", "cert.png", MediaType.IMAGE_PNG_VALUE, "fake-bytes".getBytes());

        mockMvc.perform(multipart("/instructor-applications/certificate-images")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileURL").value("https://s3.fake/instructorCertificate/x.png"));
    }

    @Test
    @DisplayName("A2: 어드민 카운트 조회 시 검수중/통과/불통과 건수와 total 이 정확하다")
    void adminCounts_areAccurate() throws Exception {
        Account admin = createAccount("ac@test.com", "adminC", Role.ADMIN);
        String adminToken = tokenFor(admin);

        // SUBMITTED 1, APPROVED 1, REJECTED 1 구성
        submitApplication(tokenFor(createAccount("c1@test.com", "diverC1", Role.STUDENT)), "PADI"); // SUBMITTED 유지
        long toApprove = submitApplication(tokenFor(createAccount("c2@test.com", "diverC2", Role.STUDENT)), "AIDA");
        long toReject = submitApplication(tokenFor(createAccount("c3@test.com", "diverC3", Role.STUDENT)), "SSI");
        mockMvc.perform(post("/admin/instructor-applications/" + toApprove + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());
        mockMvc.perform(post("/admin/instructor-applications/" + toReject + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("reason", "x")))).andExpect(status().isOk());

        mockMvc.perform(get("/admin/instructor-applications/counts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(1))
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("A3: status 를 생략하면 전체 신청이 나오고, 목록 항목에 email 이 포함된다")
    void adminList_allStatuses_withEmail() throws Exception {
        Account admin = createAccount("al@test.com", "adminL", Role.ADMIN);
        String adminToken = tokenFor(admin);
        submitApplication(tokenFor(createAccount("l1@test.com", "diverL1", Role.STUDENT)), "PADI");
        long approveId = submitApplication(tokenFor(createAccount("l2@test.com", "diverL2", Role.STUDENT)), "AIDA");
        mockMvc.perform(post("/admin/instructor-applications/" + approveId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());

        // status 생략 → SUBMITTED + APPROVED 모두 (전체 2건)
        MvcResult res = mockMvc.perform(get("/admin/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andReturn();

        // email 노출 검증 (임베디드 키 이름에 의존하지 않게 첫 배열을 직접 집는다)
        JsonNode embedded = objectMapper.readTree(res.getResponse().getContentAsString()).get("_embedded");
        JsonNode firstItem = embedded.elements().next().get(0);
        assertThat(firstItem.get("email").asText()).contains("@test.com");
    }

    @Test
    @DisplayName("A4: 승인된 신청 상세를 보면 처리한 어드민 닉네임(reviewerNickName)과 접수일시(createdAt)가 보인다")
    void adminDetail_showsReviewer() throws Exception {
        Account student = createAccount("a4@test.com", "diver16", Role.STUDENT);
        Account admin = createAccount("admin7@test.com", "심사관", Role.ADMIN);
        String adminToken = tokenFor(admin);
        long applicationId = submitApplication(tokenFor(student), "PADI");
        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());

        mockMvc.perform(get("/admin/instructor-applications/" + applicationId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerNickName").value("심사관"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /* ════════════════ B — 어드민 부트스트랩 ════════════════ */

    @Test
    @DisplayName("B1: ADMIN_EMAILS allowlist 의 이메일 계정은 부트스트랩으로 ROLE_ADMIN 이 부여된다 (idempotent, 계정 없으면 no-op)")
    void adminBootstrap_grantsRole() {
        createAccount("boot@test.com", "bootuser", Role.STUDENT);

        adminAccountInitializer.ensureAdmins(List.of("boot@test.com", "nonexistent@test.com"));

        Account promoted = accountRepo.findByEmail("boot@test.com").orElseThrow();
        assertThat(promoted.getRoles()).contains(Role.ADMIN, Role.STUDENT);

        // 두 번 실행해도 안전 (idempotent) — 예외 없이 통과
        adminAccountInitializer.ensureAdmins(List.of("boot@test.com"));
        assertThat(accountRepo.findByEmail("boot@test.com").orElseThrow().getRoles()).contains(Role.ADMIN);
    }

    /* ─── helper ─── */

    private long submitApplication(String token, String orgCode) throws Exception {
        long verificationId = verifyIdentity(token);
        MvcResult result = mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, orgCode, null, List.of("https://s3/cert1.png"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("applicationId").asLong();
    }
}
