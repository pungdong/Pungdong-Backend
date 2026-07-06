package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.AdminAccountInitializer;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.StubIdentityVerifier;
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
    @Autowired DisciplineJpaRepo disciplineRepo;
    @Autowired AdminAccountInitializer adminAccountInitializer;

    /** 출시 seed 엔 자격증 불필요 종목이 없어서, 그 코드 경로 검증용 테스트 종목을 보장한다. */
    private void ensureNonCertDiscipline(String code) {
        if (!disciplineRepo.existsByCode(code)) {
            disciplineRepo.save(Discipline.builder()
                    .code(code).name(code).requiresCertification(false).active(true).sortOrder(99).build());
        }
    }

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
        body.put("carrier", "SKT");
        body.put("method", "SMS");
        body.put("agreedRequiredTerms", true);
        return write(body);
    }

    /** 기본 종목 = FREEDIVING (자격증 필요). */
    private String submitBody(Long verificationId, String orgCode, String orgOther, List<String> urls) {
        return submitBody("FREEDIVING", verificationId, orgCode, orgOther, urls);
    }

    /** url 마다 (orgCode, orgOther, fileKey) 자격증 1건. urls=null 이면 자격증 없음(불필요 종목용). */
    private String submitBody(String disciplineCode, Long verificationId, String orgCode, String orgOther, List<String> urls) {
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", disciplineCode);
        body.put("verificationId", verificationId);
        if (urls != null) {
            List<Map<String, Object>> certs = new java.util.ArrayList<>();
            for (String url : urls) {
                Map<String, Object> c = new HashMap<>();
                c.put("organizationCode", orgCode);
                c.put("organizationOther", orgOther);
                c.put("fileKey", url);
                certs.add(c);
            }
            body.put("certificates", certs);
        }
        return write(body);
    }

    /** 여러 단체 자격증을 한 신청에 담는 제출 body — certs = [{org, fileKey}, ...]. */
    private String submitBodyMultiCert(String disciplineCode, Long verificationId, List<String[]> orgUrlPairs) {
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", disciplineCode);
        body.put("verificationId", verificationId);
        List<Map<String, Object>> certs = new java.util.ArrayList<>();
        for (String[] pair : orgUrlPairs) {
            Map<String, Object> c = new HashMap<>();
            c.put("organizationCode", pair[0]);
            c.put("fileKey", pair[1]);
            certs.add(c);
        }
        body.put("certificates", certs);
        return write(body);
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 본인확인 stub 2단계(생성=발송 → 매직 OTP 확인)를 거쳐 VERIFIED verificationId 를 받아온다. */
    private long verifyIdentity(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("verificationId").asLong();

        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("otp", StubIdentityVerifier.MAGIC_OTP))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
        return id;
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

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "FREEDIVING").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(InstructorApplicationStatus.SUBMITTED);
        assertThat(saved.getDisciplineCode()).isEqualTo("FREEDIVING");
        // 자격증/본인확인은 LAZY 연관이라 트랜잭션 밖에서 직접 만지지 않고 별도 조회로 검증
        assertThat(certificateRepo.findAll()).hasSize(1);
        assertThat(certificateRepo.findAll().get(0).getFileKey()).isEqualTo("https://s3/cert1.png");
        assertThat(certificateRepo.findAll().get(0).getOrganizationCode()).isEqualTo("PADI");
        assertThat(identityVerificationRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("S2: 신청 이력이 없는 사용자가 내 신청을 조회하면 200 + 빈 목록 (404 아님)")
    void getMyApplications_empty_whenNeverApplied() throws Exception {
        Account student = createAccount("s2@test.com", "diver2", Role.STUDENT);

        mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist()); // 빈 컬렉션
    }

    @Test
    @DisplayName("S3: 제출 후 내 신청 목록을 조회하면 그 종목 항목에 SUBMITTED + 단체·자격증·본인인증여부가 보인다")
    void getMyApplications_reflectsSubmission() throws Exception {
        Account student = createAccount("s3@test.com", "diver3", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "AIDA", null, List.of("https://s3/cert1.png", "https://s3/cert2.png"))))
                .andExpect(status().isCreated());

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        // 키는 @Relation(collectionRelation="applications") 로 고정
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(item.get("disciplineCode").asText()).isEqualTo("FREEDIVING");
        assertThat(item.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(item.get("identityVerified").asBoolean()).isTrue();
        assertThat(item.get("certificates")).hasSize(2);
        assertThat(item.get("certificates").get(0).get("organizationCode").asText()).isEqualTo("AIDA");
    }

    @Test
    @DisplayName("S4: 조회 시 자격증은 저장 key 를 그대로 돌려주고, 표시용 viewUrl(한시 발급)을 함께 내려준다")
    void getMyApplications_emitsStoredKeyAndPresignedViewUrl() throws Exception {
        Account student = createAccount("s4@test.com", "diver4", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        // 저장 참조 key → 표시용 한시 URL 변환을 스텁(운영에선 presigned GET).
        given(certificateImageStorage.viewUrl("instructorCertificate/9/cert.png"))
                .willReturn("https://s3.example/instructorCertificate/9/cert.png?X-Amz-Signature=stub");
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "AIDA", null, List.of("instructorCertificate/9/cert.png"))))
                .andExpect(status().isCreated());

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode cert = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0).get("certificates").get(0);
        // 저장 key 는 라운드트립(클라가 제출에 다시 쓰는 값), viewUrl 은 표시 전용 한시 URL
        assertThat(cert.get("fileKey").asText()).isEqualTo("instructorCertificate/9/cert.png");
        assertThat(cert.get("viewUrl").asText()).isEqualTo("https://s3.example/instructorCertificate/9/cert.png?X-Amz-Signature=stub");
    }

    @Test
    @DisplayName("S5: 보험(선택)을 첨부해 제출하면 저장되고, 조회에 insuranceFileKey + 표시용 viewUrl(한시) 을 내려준다")
    void submitWithInsurance_storedAndPresigned() throws Exception {
        Account student = createAccount("s5@test.com", "diver5", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        given(certificateImageStorage.viewUrl("instructorCertificate/9/insurance.png"))
                .willReturn("https://s3.example/insurance?X-Amz-Signature=stub");

        Map<String, Object> cert = new HashMap<>();
        cert.put("organizationCode", "AIDA");
        cert.put("fileKey", "instructorCertificate/9/cert.png");
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", "FREEDIVING");
        body.put("verificationId", verificationId);
        body.put("certificates", List.of(cert));
        body.put("insuranceFileKey", "instructorCertificate/9/insurance.png");

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(body)))
                .andExpect(status().isCreated());

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "FREEDIVING").orElseThrow();
        assertThat(saved.getInsuranceFileKey()).isEqualTo("instructorCertificate/9/insurance.png");

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(item.get("insuranceFileKey").asText()).isEqualTo("instructorCertificate/9/insurance.png");
        assertThat(item.get("insuranceViewUrl").asText()).isEqualTo("https://s3.example/insurance?X-Amz-Signature=stub");
    }

    @Test
    @DisplayName("S6: 보험은 선택이라 미첨부로 제출해도 201 이고, 조회에 insuranceFileKey/viewUrl 이 없다")
    void submitWithoutInsurance_ok() throws Exception {
        Account student = createAccount("s6@test.com", "diver6", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, "AIDA", null, List.of("instructorCertificate/9/cert.png"))))
                .andExpect(status().isCreated());

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "FREEDIVING").orElseThrow();
        assertThat(saved.getInsuranceFileKey()).isNull();

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(item.hasNonNull("insuranceFileKey")).isFalse();
        assertThat(item.hasNonNull("insuranceViewUrl")).isFalse();
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

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
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

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
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

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
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
        assertThat(resubmitted.getRejectionReason()).isNull();
        // 재제출로 자격증이 새 단체(SSI)로 교체됨
        assertThat(certificateRepo.findAll()).hasSize(1);
        assertThat(certificateRepo.findAll().get(0).getOrganizationCode()).isEqualTo("SSI");
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
    @DisplayName("U1: 자격증 이미지를 업로드하면 저장 참조 key 를 돌려준다 (2-phase 1단계)")
    void uploadCertificateImage_returnsKey() throws Exception {
        Account student = createAccount("u1@test.com", "diver15", Role.STUDENT);
        given(certificateImageStorage.store(any(), any())).willReturn("instructorCertificate/1/x.png");

        MockMultipartFile file = new MockMultipartFile(
                "image", "cert.png", MediaType.IMAGE_PNG_VALUE, "fake-bytes".getBytes());

        mockMvc.perform(multipart("/instructor-applications/certificate-images")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("instructorCertificate/1/x.png"));
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

        // email 노출 검증 (키는 @Relation 으로 "applications" 고정)
        JsonNode firstItem = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
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

    /* ════════════════ DS — 종목(discipline) ════════════════ */

    @Test
    @DisplayName("DS1: 자격증 불필요 종목(수영)은 자격증·단체 없이 제출해도 201 + SUBMITTED")
    void submit_noCertDiscipline_succeedsWithoutCertificate() throws Exception {
        ensureNonCertDiscipline("SWIMMING"); // 출시 seed 엔 없음 — 자격증 불필요 경로 검증용
        Account student = createAccount("ds1@test.com", "diverDS1", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("SWIMMING", verificationId, null, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "SWIMMING").orElseThrow();
        assertThat(saved.getDisciplineCode()).isEqualTo("SWIMMING");
        assertThat(certificateRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("DS2: 자격증 필요 종목(프리다이빙)을 자격증 없이 제출하면 400")
    void submit_certDiscipline_rejectedWithoutCertificate() throws Exception {
        Account student = createAccount("ds2@test.com", "diverDS2", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("FREEDIVING", verificationId, null, null, null)))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("DS3: 같은 계정이 종목별로 따로 신청 가능 (프리다이빙+스쿠버 2건), 같은 종목 중복은 400")
    void submit_perDiscipline_allowsMultipleDisciplines() throws Exception {
        Account student = createAccount("ds3@test.com", "diverDS3", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token); // 본인확인 1회 → 여러 종목에 재사용

        mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("FREEDIVING", verificationId, "AIDA", null, List.of("https://s3/c1.png"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("SCUBA", verificationId, "PADI", null, List.of("https://s3/c2.png"))))
                .andExpect(status().isCreated());

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).hasSize(2);

        // 같은 종목(프리다이빙) 재신청은 중복 → 400
        mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("FREEDIVING", verificationId, "SSI", null, List.of("https://s3/c3.png"))))
                .andExpect(status().is4xxClientError());

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).hasSize(2);
    }

    @Test
    @DisplayName("DS4: 한 종목 신청에 여러 단체 자격증(AIDA+PADI+Molchanovs)을 등록할 수 있다")
    void submit_multipleCertificatesAcrossOrgs() throws Exception {
        Account student = createAccount("ds4@test.com", "diverDS4", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBodyMultiCert("FREEDIVING", verificationId, List.of(
                                new String[]{"AIDA", "https://s3/aida.png"},
                                new String[]{"PADI", "https://s3/padi.png"},
                                new String[]{"MOLCHANOVS", "https://s3/mol.png"}))))
                .andExpect(status().isCreated());

        assertThat(certificateRepo.findAll()).hasSize(3);
        assertThat(certificateRepo.findAll().stream()
                .map(c -> c.getOrganizationCode()).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("AIDA", "PADI", "MOLCHANOVS");
    }

    @Test
    @DisplayName("DS5: 승인된 강사는 같은 종목 재신청은 막히고(400), 자격증 관리 탭에서 자격증만 추가(검수 없이)된다")
    void approvedInstructor_addsCertificate_andCannotReapply() throws Exception {
        Account student = createAccount("ds5@test.com", "diverDS5", Role.STUDENT);
        Account admin = createAccount("adminDS5@test.com", "adminDS5", Role.ADMIN);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        long applicationId = submitApplication(token, "AIDA"); // FREEDIVING + AIDA
        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))).andExpect(status().isOk());

        // 같은 종목 재신청 → 400 (이미 강사)
        long v2 = verifyIdentity(token);
        mockMvc.perform(post("/instructor-applications").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("FREEDIVING", v2, "SSI", null, List.of("https://s3/x.png"))))
                .andExpect(status().is4xxClientError());

        // 자격증 관리 탭: 자격증만 추가 (검수 없이 즉시) → 200, status APPROVED 유지
        Map<String, Object> addBody = new HashMap<>();
        addBody.put("disciplineCode", "FREEDIVING");
        addBody.put("organizationCode", "PADI");
        addBody.put("fileKey", "https://s3/padi-new.png");
        mockMvc.perform(post("/instructor-applications/certificates").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(addBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 이제 자격증 2건 (AIDA + PADI), 신청은 여전히 1건 APPROVED
        assertThat(certificateRepo.findAll()).hasSize(2);
        assertThat(applicationRepo.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(InstructorApplicationStatus.APPROVED);
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
