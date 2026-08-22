package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.AdminAccountInitializer;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.certificate.CertificateReview;
import com.diving.pungdong.certificate.CertificateReviewJpaRepo;
import com.diving.pungdong.certificate.CertificateReviewKind;
import com.diving.pungdong.certificate.CertificateReviewStatus;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.StubIdentityVerifier;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 생기고, 승인 시 권한과 <b>자격증 검증 상태</b>가 어떻게 바뀌는가" 를 검증한다. 외부 경계인 S3 만
 * {@code @MockBean}. 본인확인은 {@link StubIdentityVerifier} (우리 stub) 를 그대로 사용.
 *
 * <p>2026-08-22 수렴: 신청은 자격증을 소유하지 않고 <b>내 자격증({@code /certificates}) 의 id 를 참조</b>한다.
 * 자격증 픽스처는 repo 로 직접 넣는다(등록 HTTP 경로는 {@code StudentCertificateUseCaseTest} 가 검증한다).
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 의 한글 시나리오를 위에서 아래로 읽으면 강사 신청
 * 사양이 된다. 그룹 — S* 성공 / V* 검증거절 / D* 중복 / R* 권한 / J* 반려·재제출 / A* 어드민목록 /
 * DS* 종목 / RB* 자격증 검증(Rule B).
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
    @Autowired StudentCertificateJpaRepo certificateRepo;
    @Autowired CertificateReviewJpaRepo reviewRepo;
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
        reviewRepo.deleteAll();
        applicationRepo.deleteAll();
        certificateRepo.deleteAll();
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

    /** 보험 업로드가 돌려주는 저장 참조 — {@code instructorCertificate/{ownerId}/{name}}. 소유 검증이 실제 모양을 요구한다. */
    private String key(Account owner, String name) {
        return "instructorCertificate/" + owner.getId() + "/" + name;
    }

    /** 내 자격증 1장(검증 상태 NONE). 기본은 강사 레벨. */
    private StudentCertificate certificate(Account owner, String disciplineCode, String org, CertLevel level) {
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(org).organizationName(org)
                .level(level).certificateNumber(org + "-1").acquiredAt(LocalDate.of(2021, 3, 1))
                .source(CertificateSource.EXTERNAL)
                .photoFileKey("studentCertificate/" + owner.getId() + "/" + org.toLowerCase() + ".jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private StudentCertificate instructorCert(Account owner, String disciplineCode, String org) {
        return certificate(owner, disciplineCode, org, CertLevel.INSTRUCTOR);
    }

    private StudentCertificate reload(StudentCertificate c) {
        return certificateRepo.findById(c.getId()).orElseThrow();
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
    private String submitBody(Long verificationId, List<Long> certificateIds) {
        return submitBody("FREEDIVING", verificationId, certificateIds);
    }

    /** certificateIds=null 이면 필드 자체를 생략(불필요 종목용). */
    private String submitBody(String disciplineCode, Long verificationId, List<Long> certificateIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", disciplineCode);
        body.put("verificationId", verificationId);
        if (certificateIds != null) {
            body.put("certificateIds", certificateIds);
        }
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

    /** OTP 확인을 거치지 않은 <b>READY</b> 본인확인을 생성해 그 id 만 반환 — 미완료 verificationId 게이트 검증용. */
    private long createUnconfirmedIdentity(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("verificationId").asLong();
    }

    private MvcResult submit(String token, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void approve(Account admin, long applicationId) throws Exception {
        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk());
    }

    private void reject(Account admin, long applicationId, String reason) throws Exception {
        mockMvc.perform(post("/admin/instructor-applications/" + applicationId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("reason", reason))))
                .andExpect(status().isOk());
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 본인확인 → 내 자격증 id 로 신청 제출하면 201 + SUBMITTED, 그 자격증은 PENDING(APPLICATION) + 검수 큐에 NEW 행")
    void submit_succeeds() throws Exception {
        Account student = createAccount("s1@test.com", "diver1", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");

        submit(token, submitBody(verificationId, List.of(cert.getId())), 201);

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "FREEDIVING").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(InstructorApplicationStatus.SUBMITTED);
        assertThat(saved.getDisciplineCode()).isEqualTo("FREEDIVING");

        StudentCertificate pending = reload(cert);
        assertThat(pending.getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.PENDING);
        assertThat(pending.getVerification().getKind()).isEqualTo(CertificateVerificationKind.APPLICATION);
        assertThat(pending.getVerification().getRequestedAt()).isNotNull();

        CertificateReview review = reviewRepo.findFirstByApplicationIdAndStatus(saved.getId(), CertificateReviewStatus.PENDING).orElseThrow();
        assertThat(review.getKind()).isEqualTo(CertificateReviewKind.NEW);
        assertThat(review.getAccountId()).isEqualTo(student.getId());
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
    @DisplayName("S3: 제출 후 내 신청 목록을 조회하면 그 종목 항목에 SUBMITTED + 첨부 certificateIds(제출 순서) + 본인인증여부가 보인다")
    void getMyApplications_reflectsSubmission() throws Exception {
        Account student = createAccount("s3@test.com", "diver3", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate aida = instructorCert(student, "FREEDIVING", "AIDA");
        StudentCertificate padi = instructorCert(student, "FREEDIVING", "PADI");
        submit(token, submitBody(verificationId, List.of(padi.getId(), aida.getId())), 201);

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(item.get("disciplineCode").asText()).isEqualTo("FREEDIVING");
        assertThat(item.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(item.get("identityVerified").asBoolean()).isTrue();
        assertThat(item.get("certificateIds")).hasSize(2);
        assertThat(item.get("certificateIds").get(0).asLong()).isEqualTo(padi.getId());
        assertThat(item.get("certificateIds").get(1).asLong()).isEqualTo(aida.getId());
    }

    @Test
    @DisplayName("S4: 신청 전에 미리 올려둔 그 종목의 강사레벨 자격증은 certificateIds 에서 빼도 자동 첨부된다 (어드민이 한 번에 다 본다)")
    void submit_autoAttachesPreRegisteredInstructorCertificates() throws Exception {
        Account student = createAccount("s4@test.com", "diver4", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate sent = instructorCert(student, "FREEDIVING", "AIDA");
        StudentCertificate forgotten = instructorCert(student, "FREEDIVING", "PADI");
        StudentCertificate otherDiscipline = instructorCert(student, "SCUBA", "SSI");     // 다른 종목 — 안 붙는다
        StudentCertificate studentLevel = certificate(student, "FREEDIVING", "MOLCHANOVS", CertLevel.LEVEL_2); // 수강생 레벨 — 안 붙는다

        submit(token, submitBody(verificationId, List.of(sent.getId())), 201);

        assertThat(attachedIds(student, "FREEDIVING")).containsExactly(sent.getId(), forgotten.getId());
        assertThat(reload(forgotten).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.PENDING);
        assertThat(reload(otherDiscipline).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
        assertThat(reload(studentLevel).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
    }

    @Test
    @DisplayName("S5: 보험(선택)을 첨부해 제출하면 저장되고, 조회에 insuranceFileKey + 표시용 viewUrl(한시) 을 내려준다")
    void submitWithInsurance_storedAndPresigned() throws Exception {
        Account student = createAccount("s5@test.com", "diver5", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "AIDA");
        given(certificateImageStorage.viewUrl(key(student, "insurance.png")))
                .willReturn("https://s3.example/insurance?X-Amz-Signature=stub");

        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", "FREEDIVING");
        body.put("verificationId", verificationId);
        body.put("certificateIds", List.of(cert.getId()));
        body.put("insuranceFileKey", key(student, "insurance.png"));
        submit(token, write(body), 201);

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "FREEDIVING").orElseThrow();
        assertThat(saved.getInsuranceFileKey()).isEqualTo(key(student, "insurance.png"));

        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(item.get("insuranceFileKey").asText()).isEqualTo(key(student, "insurance.png"));
        assertThat(item.get("insuranceViewUrl").asText()).isEqualTo("https://s3.example/insurance?X-Amz-Signature=stub");
    }

    @Test
    @DisplayName("S6: 보험은 선택이라 미첨부로 제출해도 201 이고, 조회에 insuranceFileKey/viewUrl 이 없다")
    void submitWithoutInsurance_ok() throws Exception {
        Account student = createAccount("s6@test.com", "diver6", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "AIDA");
        submit(token, submitBody(verificationId, List.of(cert.getId())), 201);

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
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");

        submit(token, submitBody(null, List.of(cert.getId())), 400);

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("V1b: 미완료(READY) 본인확인 id 로 제출하면 403(-1017, 본인인증 선행) + DB 에 신청 안 생김")
    void submit_rejectedWithUnverifiedVerification() throws Exception {
        Account student = createAccount("v1b@test.com", "diver4b", Role.STUDENT);
        String token = tokenFor(student);
        long unverifiedId = createUnconfirmedIdentity(token); // 발송만 하고 OTP 확인 안 함 → READY
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(unverifiedId, List.of(cert.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(-1017));

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("V2: 자격증 필요 종목에 첨부 0장(자동 첨부될 것도 없음)으로 제출하면 400 + msg, 신청·검수행 안 생김")
    void submit_rejectedWithoutCertificates() throws Exception {
        Account student = createAccount("v2@test.com", "diver5", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("강사 레벨 자격증을 1개 이상 등록해주세요."));

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
        assertThat(reviewRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V3: 수강생 레벨(LEVEL_2) 자격증 id 로 제출하면 400 + \"강사 레벨 자격증만\" msg")
    void submit_rejectsStudentLevelCertificate() throws Exception {
        Account student = createAccount("v3@test.com", "diver6", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate level2 = certificate(student, "FREEDIVING", "AIDA", CertLevel.LEVEL_2);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, List.of(level2.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("강사 레벨 자격증만 강사 신청에 쓸 수 있어요."));

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
        assertThat(reload(level2).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
    }

    @Test
    @DisplayName("V4: 남의 자격증 id 를 자기 신청에 붙여 제출하면 400 + 신청 안 생김 + 남의 자격증 상태 불변 (존재 숨김)")
    void submit_rejectsOtherPersonsCertificate() throws Exception {
        Account victim = createAccount("v4-victim@test.com", "diverV4a", Role.STUDENT);
        Account attacker = createAccount("v4@test.com", "diverV4b", Role.STUDENT);
        String token = tokenFor(attacker);
        long verificationId = verifyIdentity(token);
        StudentCertificate victims = instructorCert(victim, "FREEDIVING", "AIDA");

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(verificationId, List.of(victims.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("등록되지 않은 자격증이 있어요. 내 자격증을 확인해주세요."));

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(attacker.getId())).isEmpty();
        assertThat(reload(victims).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
    }

    @Test
    @DisplayName("V5: 보험 이미지가 남의 fileKey 면 400 (보험 업로드 경로의 소유 검사)")
    void submit_rejectsOtherPersonsInsuranceFileKey() throws Exception {
        Account victim = createAccount("v5-victim@test.com", "diverV5a", Role.STUDENT);
        Account attacker = createAccount("v5@test.com", "diverV5b", Role.STUDENT);
        String token = tokenFor(attacker);
        long verificationId = verifyIdentity(token);
        StudentCertificate mine = instructorCert(attacker, "FREEDIVING", "AIDA");

        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", "FREEDIVING");
        body.put("verificationId", verificationId);
        body.put("certificateIds", List.of(mine.getId()));
        body.put("insuranceFileKey", key(victim, "leaked-insurance.png")); // 보험만 남의 것
        submit(token, write(body), 400);

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(attacker.getId())).isEmpty();
    }

    @Test
    @DisplayName("V6: 다른 종목(스쿠버) 자격증 id 를 프리다이빙 신청에 붙이면 400 + 종목 불일치 msg")
    void submit_rejectsDisciplineMismatch() throws Exception {
        Account student = createAccount("v6@test.com", "diverV6", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate scuba = instructorCert(student, "SCUBA", "PADI");

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("FREEDIVING", verificationId, List.of(scuba.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("자격증의 종목이 신청 종목과 달라요."));

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
    }

    /* ════════════════ D — 중복 ════════════════ */

    @Test
    @DisplayName("D1: 이미 심사중(SUBMITTED) 신청이 있는데 또 제출하면 400 (중복 신청 불가)")
    void submit_rejectsDuplicateWhilePending() throws Exception {
        Account student = createAccount("d1@test.com", "diver7", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate first = instructorCert(student, "FREEDIVING", "PADI");
        submit(token, submitBody(verificationId, List.of(first.getId())), 201);

        long verificationId2 = verifyIdentity(token);
        StudentCertificate second = instructorCert(student, "FREEDIVING", "SSI");
        submit(token, submitBody(verificationId2, List.of(second.getId())), 400);

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
    @DisplayName("R2: 어드민이 SUBMITTED 신청을 승인하면 200 + INSTRUCTOR 추가 + isCertified=true + APPROVED + 첨부 자격증 VERIFIED(=인증마크)")
    void approve_grantsInstructorRole() throws Exception {
        Account student = createAccount("r2@test.com", "diver9", Role.STUDENT);
        Account admin = createAccount("admin2@test.com", "admin2", Role.ADMIN);
        String studentToken = tokenFor(student);
        long verificationId = verifyIdentity(studentToken);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");
        MvcResult submitted = submit(studentToken, submitBody(verificationId, List.of(cert.getId())), 201);
        long applicationId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("applicationId").asLong();

        approve(admin, applicationId);

        Account approved = accountRepo.findById(student.getId()).orElseThrow();
        assertThat(approved.getRoles()).contains(Role.INSTRUCTOR, Role.STUDENT);
        assertThat(approved.getIsCertified()).isTrue();
        assertThat(applicationRepo.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(InstructorApplicationStatus.APPROVED);

        StudentCertificate verified = reload(cert);
        assertThat(verified.getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.VERIFIED);
        assertThat(verified.getVerification().getKind()).isEqualTo(CertificateVerificationKind.APPLICATION);
        assertThat(verified.getVerification().getReviewedAt()).isNotNull();
        CertificateReview review = reviewRepo.findAll().get(0);
        assertThat(review.getStatus()).isEqualTo(CertificateReviewStatus.APPROVED);
        assertThat(review.getReviewerId()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("R3: 승인 직후 그 사용자가 토큰 재발급 없이 강사 전용 API 를 호출하면 통과한다 (DB기반 권한)")
    void approvedUser_passesInstructorEndpoint_withOldToken() throws Exception {
        Account student = createAccount("r3@test.com", "diver10", Role.STUDENT);
        Account admin = createAccount("admin3@test.com", "admin3", Role.ADMIN);
        String oldStudentToken = tokenFor(student); // 승인 전 발급된 토큰
        long verificationId = verifyIdentity(oldStudentToken);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");
        MvcResult submitted = submit(oldStudentToken, submitBody(verificationId, List.of(cert.getId())), 201);
        long applicationId = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("applicationId").asLong();

        // 승인 전에는 강사 전용 API 가 403
        mockMvc.perform(get("/account/instructor/certificate/list")
                        .header(HttpHeaders.AUTHORIZATION, oldStudentToken))
                .andExpect(status().isForbidden());

        approve(admin, applicationId);

        // 승인 후, 같은(옛) 토큰으로 강사 전용 API 호출 → 통과 (권한이 매 요청 DB 에서 재계산되므로)
        mockMvc.perform(get("/account/instructor/certificate/list")
                        .header(HttpHeaders.AUTHORIZATION, oldStudentToken))
                .andExpect(status().isOk());
    }

    /* ════════════════ J — 반려·재제출 ════════════════ */

    @Test
    @DisplayName("J1: 어드민이 사유를 담아 반려하면 status=REJECTED + 사유 저장 + 첨부 자격증도 REJECTED 에 같은 사유")
    void reject_storesReason() throws Exception {
        Account student = createAccount("j1@test.com", "diver11", Role.STUDENT);
        Account admin = createAccount("admin4@test.com", "admin4", Role.ADMIN);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");
        long applicationId = submitApplication(student, cert);

        reject(admin, applicationId, "자격증 사진이 흐릿합니다");

        InstructorApplication rejected = applicationRepo.findById(applicationId).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(InstructorApplicationStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("자격증 사진이 흐릿합니다");
        assertThat(reload(cert).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.REJECTED);
        assertThat(reload(cert).getVerification().getReason()).isEqualTo("자격증 사진이 흐릿합니다");
        assertThat(reviewRepo.findAll().get(0).getStatus()).isEqualTo(CertificateReviewStatus.REJECTED);
    }

    @Test
    @DisplayName("J2: 반려된 신청을 PUT 으로 다른 자격증으로 재제출하면 SUBMITTED 복귀 — 새 자격증 PENDING, 빠진 옛 자격증은 NONE")
    void resubmit_afterRejection() throws Exception {
        Account student = createAccount("j2@test.com", "diver12", Role.STUDENT);
        Account admin = createAccount("admin5@test.com", "admin5", Role.ADMIN);
        String studentToken = tokenFor(student);
        StudentCertificate old = instructorCert(student, "FREEDIVING", "PADI");
        long applicationId = submitApplication(student, old);
        reject(admin, applicationId, "재촬영 필요");

        long newVerification = verifyIdentity(studentToken);
        StudentCertificate fresh = instructorCert(student, "FREEDIVING", "SSI");
        mockMvc.perform(put("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(newVerification, List.of(fresh.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        InstructorApplication resubmitted = applicationRepo.findById(applicationId).orElseThrow();
        assertThat(resubmitted.getStatus()).isEqualTo(InstructorApplicationStatus.SUBMITTED);
        assertThat(resubmitted.getRejectionReason()).isNull();
        assertThat(attachedIds(student, "FREEDIVING")).containsExactly(fresh.getId());
        assertThat(reload(fresh).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.PENDING);
        assertThat(reload(old).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
        // 검수 큐: 옛 NEW 행은 REJECTED 이력으로 남고, 새 NEW 행이 PENDING
        assertThat(reviewRepo.countByStatus(CertificateReviewStatus.PENDING)).isEqualTo(1);
        assertThat(reviewRepo.countByStatus(CertificateReviewStatus.REJECTED)).isEqualTo(1);
    }

    /* ════════════════ A — 어드민 목록 ════════════════ */

    @Test
    @DisplayName("A1: 어드민이 ?status=SUBMITTED 목록을 조회하면 대기중만 나오고 승인된 건은 빠진다")
    void adminList_showsOnlyPending() throws Exception {
        Account pending = createAccount("a1@test.com", "diver13", Role.STUDENT);
        Account approvedUser = createAccount("a2@test.com", "diver14", Role.STUDENT);
        Account admin = createAccount("admin6@test.com", "admin6", Role.ADMIN);
        String adminToken = tokenFor(admin);

        submitApplication(pending, instructorCert(pending, "FREEDIVING", "PADI"));
        long approvedAppId = submitApplication(approvedUser, instructorCert(approvedUser, "FREEDIVING", "AIDA"));
        approve(admin, approvedAppId);

        mockMvc.perform(get("/admin/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .param("status", "SUBMITTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.applications[0].organizationCodes[0]").value("PADI"));

        assertThat(applicationRepo.findAll()).hasSize(2);
    }

    /* ════════════════ U — 업로드 (보험, 2-phase 1단계) ════════════════ */

    @Test
    @DisplayName("U1: 보험 이미지를 업로드하면 저장 참조 key 를 돌려준다 (2-phase 1단계)")
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

        Account c1 = createAccount("c1@test.com", "diverC1", Role.STUDENT);
        Account c2 = createAccount("c2@test.com", "diverC2", Role.STUDENT);
        Account c3 = createAccount("c3@test.com", "diverC3", Role.STUDENT);
        submitApplication(c1, instructorCert(c1, "FREEDIVING", "PADI")); // SUBMITTED 유지
        long toApprove = submitApplication(c2, instructorCert(c2, "FREEDIVING", "AIDA"));
        long toReject = submitApplication(c3, instructorCert(c3, "FREEDIVING", "SSI"));
        approve(admin, toApprove);
        reject(admin, toReject, "x");

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
        Account l1 = createAccount("l1@test.com", "diverL1", Role.STUDENT);
        Account l2 = createAccount("l2@test.com", "diverL2", Role.STUDENT);
        submitApplication(l1, instructorCert(l1, "FREEDIVING", "PADI"));
        long approveId = submitApplication(l2, instructorCert(l2, "FREEDIVING", "AIDA"));
        approve(admin, approveId);

        MvcResult res = mockMvc.perform(get("/admin/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andReturn();

        JsonNode firstItem = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("applications").get(0);
        assertThat(firstItem.get("email").asText()).contains("@test.com");
    }

    @Test
    @DisplayName("A4: 승인된 신청 상세엔 처리 어드민(reviewerNickName)·접수일시 + 첨부 자격증 풀 필드·검증 상태가 보인다")
    void adminDetail_showsReviewerAndCertificates() throws Exception {
        Account student = createAccount("a4@test.com", "diver16", Role.STUDENT);
        Account admin = createAccount("admin7@test.com", "심사관", Role.ADMIN);
        String adminToken = tokenFor(admin);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "PADI");
        long applicationId = submitApplication(student, cert);
        approve(admin, applicationId);

        mockMvc.perform(get("/admin/instructor-applications/" + applicationId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerNickName").value("심사관"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.certificates[0].certificateId").value(cert.getId()))
                .andExpect(jsonPath("$.certificates[0].organizationCode").value("PADI"))
                .andExpect(jsonPath("$.certificates[0].level").value("INSTRUCTOR"))
                .andExpect(jsonPath("$.certificates[0].certificateNumber").value("PADI-1"))
                .andExpect(jsonPath("$.certificates[0].holderName").value("김다이버")) // 본인확인 실명
                .andExpect(jsonPath("$.certificates[0].photoViewUrl").exists())
                .andExpect(jsonPath("$.certificates[0].verification.status").value("VERIFIED"));
    }

    /* ════════════════ B — 어드민 부트스트랩 ════════════════ */

    @Test
    @DisplayName("B1: ADMIN_EMAILS allowlist 의 이메일 계정은 부트스트랩으로 ROLE_ADMIN 이 부여된다 (idempotent, 계정 없으면 no-op)")
    void adminBootstrap_grantsRole() {
        createAccount("boot@test.com", "bootuser", Role.STUDENT);

        adminAccountInitializer.ensureAdmins(List.of("boot@test.com", "nonexistent@test.com"));

        Account promoted = accountRepo.findByEmail("boot@test.com").orElseThrow();
        assertThat(promoted.getRoles()).contains(Role.ADMIN, Role.STUDENT);

        adminAccountInitializer.ensureAdmins(List.of("boot@test.com"));
        assertThat(accountRepo.findByEmail("boot@test.com").orElseThrow().getRoles()).contains(Role.ADMIN);
    }

    /* ════════════════ DS — 종목(discipline) ════════════════ */

    @Test
    @DisplayName("DS1: 자격증 불필요 종목(수영)은 자격증 없이 제출해도 201 + SUBMITTED, 검수 큐엔 NEW 행이 생긴다")
    void submit_noCertDiscipline_succeedsWithoutCertificate() throws Exception {
        ensureNonCertDiscipline("SWIMMING");
        Account student = createAccount("ds1@test.com", "diverDS1", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        mockMvc.perform(post("/instructor-applications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody("SWIMMING", verificationId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        InstructorApplication saved = applicationRepo.findByAccountIdAndDisciplineCode(student.getId(), "SWIMMING").orElseThrow();
        assertThat(attachedIds(student, "SWIMMING")).isEmpty();
        assertThat(reviewRepo.findFirstByApplicationIdAndStatus(saved.getId(), CertificateReviewStatus.PENDING)).isPresent();
    }

    @Test
    @DisplayName("DS2: 자격증 필요 종목(프리다이빙)을 자격증 없이 제출하면 400")
    void submit_certDiscipline_rejectedWithoutCertificate() throws Exception {
        Account student = createAccount("ds2@test.com", "diverDS2", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);

        submit(token, submitBody("FREEDIVING", verificationId, null), 400);

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).isEmpty();
    }

    @Test
    @DisplayName("DS3: 같은 계정이 종목별로 따로 신청 가능 (프리다이빙+스쿠버 2건), 같은 종목 중복은 400")
    void submit_perDiscipline_allowsMultipleDisciplines() throws Exception {
        Account student = createAccount("ds3@test.com", "diverDS3", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token); // 본인확인 1회 → 여러 종목에 재사용
        StudentCertificate aida = instructorCert(student, "FREEDIVING", "AIDA");
        StudentCertificate padi = instructorCert(student, "SCUBA", "PADI");

        submit(token, submitBody("FREEDIVING", verificationId, List.of(aida.getId())), 201);
        submit(token, submitBody("SCUBA", verificationId, List.of(padi.getId())), 201);

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).hasSize(2);

        StudentCertificate ssi = instructorCert(student, "FREEDIVING", "SSI");
        submit(token, submitBody("FREEDIVING", verificationId, List.of(ssi.getId())), 400);

        assertThat(applicationRepo.findByAccountIdOrderByIdDesc(student.getId())).hasSize(2);
    }

    @Test
    @DisplayName("DS4: 한 종목 신청에 여러 단체 자격증(AIDA+PADI+Molchanovs)을 붙일 수 있고 전부 PENDING 이 된다")
    void submit_multipleCertificatesAcrossOrgs() throws Exception {
        Account student = createAccount("ds4@test.com", "diverDS4", Role.STUDENT);
        String token = tokenFor(student);
        long verificationId = verifyIdentity(token);
        StudentCertificate a = instructorCert(student, "FREEDIVING", "AIDA");
        StudentCertificate p = instructorCert(student, "FREEDIVING", "PADI");
        StudentCertificate m = instructorCert(student, "FREEDIVING", "MOLCHANOVS");

        submit(token, submitBody(verificationId, List.of(a.getId(), p.getId(), m.getId())), 201);

        assertThat(attachedIds(student, "FREEDIVING")).containsExactly(a.getId(), p.getId(), m.getId());
        assertThat(certificateRepo.findAll()).allMatch(c -> c.getVerification().getStatus() == CertificateVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("DS5: 승인된 강사는 같은 종목 재신청이 400 이고, 추가 자격증은 내 자격증에 등록하면 검수 큐(ADDITIONAL)로 간다")
    void approvedInstructor_cannotReapply_additionalCertificateGoesToQueue() throws Exception {
        Account student = createAccount("ds5@test.com", "diverDS5", Role.STUDENT);
        Account admin = createAccount("adminDS5@test.com", "adminDS5", Role.ADMIN);
        String token = tokenFor(student);
        long applicationId = submitApplication(student, instructorCert(student, "FREEDIVING", "AIDA"));
        approve(admin, applicationId);

        // 같은 종목 재신청 → 400 (이미 강사)
        long v2 = verifyIdentity(token);
        StudentCertificate ssi = instructorCert(student, "FREEDIVING", "SSI");
        submit(token, submitBody("FREEDIVING", v2, List.of(ssi.getId())), 400);

        // 대신 내 자격증에 강사레벨 자격증을 올리면(Rule A) 곧장 PENDING(ADDITIONAL) + 검수 행
        MockMultipartFile photo = new MockMultipartFile("image", "padi.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes".getBytes());
        MvcResult up = mockMvc.perform(multipart("/certificates/photos").file(photo)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn();
        String fileKey = objectMapper.readTree(up.getResponse().getContentAsString()).get("fileKey").asText();
        Map<String, Object> body = new HashMap<>();
        body.put("disciplineCode", "FREEDIVING");
        body.put("organizationCode", "PADI");
        body.put("organizationName", "PADI");
        body.put("level", "INSTRUCTOR");
        body.put("certificateNumber", "PADI-NEW");
        body.put("acquiredAt", "2024-01-10");
        body.put("photoFileKey", fileKey);
        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(write(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verification.status").value("PENDING"))
                .andExpect(jsonPath("$.verification.kind").value("ADDITIONAL"));

        assertThat(reviewRepo.countByStatus(CertificateReviewStatus.PENDING)).isEqualTo(1);
        assertThat(reviewRepo.findAll().stream().filter(r -> r.getKind() == CertificateReviewKind.ADDITIONAL).count()).isEqualTo(1);
        assertThat(applicationRepo.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(InstructorApplicationStatus.APPROVED);
    }

    /* ════════════════ RB — 자격증 검증 (Rule B) ════════════════ */

    @Test
    @DisplayName("RB1: 심사 중에 같은 종목 강사레벨 자격증을 더 올려두면(NONE), 승인 시 sweep 으로 PENDING(ADDITIONAL) 큐에 들어간다")
    void approve_sweepsCertificatesRegisteredDuringReview() throws Exception {
        Account student = createAccount("rb1@test.com", "diverRB1", Role.STUDENT);
        Account admin = createAccount("adminRB1@test.com", "adminRB1", Role.ADMIN);
        StudentCertificate attached = instructorCert(student, "FREEDIVING", "AIDA");
        long applicationId = submitApplication(student, attached);
        // 심사 중(SUBMITTED)에 올린 자격증 — 신청엔 안 묶인다(NONE). 승인 전엔 큐에도 없다.
        StudentCertificate lateOne = instructorCert(student, "FREEDIVING", "PADI");
        StudentCertificate otherDiscipline = instructorCert(student, "SCUBA", "SSI");
        assertThat(reviewRepo.countByStatus(CertificateReviewStatus.PENDING)).isEqualTo(1);

        approve(admin, applicationId);

        assertThat(reload(attached).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.VERIFIED);
        assertThat(reload(lateOne).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.PENDING);
        assertThat(reload(lateOne).getVerification().getKind()).isEqualTo(CertificateVerificationKind.ADDITIONAL);
        assertThat(reload(otherDiscipline).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.NONE);
        List<CertificateReview> pending = reviewRepo.findAll().stream()
                .filter(r -> r.getStatus() == CertificateReviewStatus.PENDING).toList();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getKind()).isEqualTo(CertificateReviewKind.ADDITIONAL);
        assertThat(pending.get(0).getCertificateId()).isEqualTo(lateOne.getId());
    }

    @Test
    @DisplayName("RB2: 반려 후 재제출에 같은 자격증을 다시 붙이면 REJECTED → PENDING 으로 돌아오고 사유는 지워진다")
    void resubmit_reattachingRejectedCertificate_resetsToPending() throws Exception {
        Account student = createAccount("rb2@test.com", "diverRB2", Role.STUDENT);
        Account admin = createAccount("adminRB2@test.com", "adminRB2", Role.ADMIN);
        String token = tokenFor(student);
        StudentCertificate cert = instructorCert(student, "FREEDIVING", "AIDA");
        long applicationId = submitApplication(student, cert);
        reject(admin, applicationId, "사진 흐림");
        assertThat(reload(cert).getVerification().getReason()).isEqualTo("사진 흐림");

        long v2 = verifyIdentity(token);
        mockMvc.perform(put("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(v2, List.of(cert.getId()))))
                .andExpect(status().isOk());

        assertThat(reload(cert).getVerification().getStatus()).isEqualTo(CertificateVerificationStatus.PENDING);
        assertThat(reload(cert).getVerification().getReason()).isNull();
    }

    /* ─── helper ─── */

    /** 첨부 id — LAZY 컬렉션이라 엔티티에서 직접 읽지 않고 GET /me 응답(FE 가 보는 그 값)으로 읽는다. */
    private List<Long> attachedIds(Account owner, String disciplineCode) throws Exception {
        MvcResult res = mockMvc.perform(get("/instructor-applications/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(owner)))
                .andExpect(status().isOk()).andReturn();
        JsonNode apps = objectMapper.readTree(res.getResponse().getContentAsString()).get("_embedded").get("applications");
        for (JsonNode app : apps) {
            if (disciplineCode.equals(app.get("disciplineCode").asText())) {
                List<Long> ids = new java.util.ArrayList<>();
                app.get("certificateIds").forEach(n -> ids.add(n.asLong()));
                return ids;
            }
        }
        throw new AssertionError("no application for " + disciplineCode);
    }

    private long submitApplication(Account applicant, StudentCertificate certificate) throws Exception {
        String token = tokenFor(applicant);
        long verificationId = verifyIdentity(token);
        MvcResult result = submit(token, submitBody(certificate.getDisciplineCode(), verificationId,
                List.of(certificate.getId())), 201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("applicationId").asLong();
    }
}
