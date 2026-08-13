package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountAnonymizationService;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 보유 자격증 use-case — 조회 / 등록(외부·강의연결) / 삭제 / 사진 업로드.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / V* 검증거절 / R* 권한 / A* 탈퇴 파기.
 *
 * <p>실 H2 + 실 시큐리티 체인 + <b>실제 로컬 스토리지</b>(S3 미접속, mock 아님 — test 프로파일은
 * {@code pungdong.storage.s3.enabled} 미설정이라 local stub 이 활성). 수강 픽스처는 예약 HTTP 플로우
 * (venue·coverage·본인확인)를 태우지 않고 repo 로 직접 만든다 — 검증 대상이 자격증이지 예약이 아니다.
 * ⚠️ {@code Authorization} 은 raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentCertificateUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired StudentCertificateJpaRepo certificateRepo;
    @Autowired AccountAnonymizationService anonymizationService;

    @Value("${pungdong.storage.local.dir:local-uploads}")
    String localDir;

    @AfterEach
    void cleanUp() {
        certificateRepo.deleteAll();
        enrollmentRepo.deleteAll();
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─────────────────────────────────────────── */

    private Account account(String email, String nick, Role role) {
        return accountRepo.save(Account.builder().email(email).password("enc").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    /** 정규 1회차 코스. */
    private Course course(Account instructor, String disciplineCode, String org) {
        Course c = Course.builder().instructor(instructor).title("AIDA2 자격 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode(org).disciplineCode(disciplineCode)
                .totalRounds(1).price(300000).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        return courseRepo.save(c);
    }

    /** 수강 1건 — {@code done=true} 면 정규회차가 이수 처리돼 "완료 강의"가 된다. */
    private Enrollment enrollment(Account student, Course course, boolean done, LocalDate roundDate) {
        Enrollment e = Enrollment.builder().student(student).course(course)
                .tuitionSnapshot(course.getPrice()).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        EnrollmentRound r = EnrollmentRound.builder()
                .roundKind(RoundKind.REGULAR).roundIndex(1).date(roundDate)
                .status(done ? EnrollmentStatus.CONFIRMED : EnrollmentStatus.PENDING)
                .doneAt(done ? OffsetDateTime.now(ZoneOffset.UTC) : null)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        e.addRound(r);
        return enrollmentRepo.save(e);
    }

    private Map<String, Object> body(String disciplineCode, String org, String level, String number, String acquiredAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("disciplineCode", disciplineCode);
        m.put("organizationCode", org);
        m.put("organizationName", org);
        m.put("organizationFullName", "AIDA INTERNATIONAL");
        m.put("level", level);
        m.put("certificationDisplayName", "AIDA 2");
        m.put("certificateNumber", number);
        m.put("acquiredAt", acquiredAt);
        return m;
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String register(Account who, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(who))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** 실제 업로드를 태워 저장 참조를 얻는다(로컬 스토리지에 파일이 실제로 쓰인다). */
    private String uploadPhoto(Account who, String fileName) throws Exception {
        MockMultipartFile file = new MockMultipartFile("image", fileName, MediaType.IMAGE_JPEG_VALUE, "bytes".getBytes());
        MvcResult res = mockMvc.perform(multipart("/certificates/photos").file(file)
                        .header(HttpHeaders.AUTHORIZATION, token(who)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("fileKey").asText();
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 강의 연결 없이 등록하면 201 + source=EXTERNAL 로 저장된다 (외부 취득)")
    void register_external() throws Exception {
        Account student = account("c-s1@test.com", "diverS1", Role.STUDENT);

        register(student, body("FREEDIVING", "AIDA", "LEVEL_2", "AIDA-2024-12345", "2024-11-02"));

        StudentCertificate saved = certificateRepo.findAll().get(0);
        assertThat(saved.getSource()).isEqualTo(CertificateSource.EXTERNAL);
        assertThat(saved.getLevel()).isEqualTo(CertLevel.LEVEL_2);
        assertThat(saved.getAcquiredAt()).isEqualTo(LocalDate.of(2024, 11, 2));
        // 강의 스냅샷은 비어 있다
        assertThat(saved.getEnrollmentId()).isNull();
        assertThat(saved.getInstructorName()).isNull();
    }

    @Test
    @DisplayName("S2: 완료한 강의를 연결해 등록하면 source=PUNGDONG + 강사·강의가 서버에서 박제된다")
    void register_linkedToCompletedCourse() throws Exception {
        Account instructor = account("c-s2i@test.com", "김민지", Role.INSTRUCTOR);
        Account student = account("c-s2@test.com", "diverS2", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        LocalDate roundDate = LocalDate.now().minusDays(3);
        Enrollment e = enrollment(student, course, true, roundDate);

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "AIDA-2024-1", "2024-11-02");
        payload.put("enrollmentId", e.getId());
        register(student, payload);

        StudentCertificate saved = certificateRepo.findAll().get(0);
        assertThat(saved.getSource()).isEqualTo(CertificateSource.PUNGDONG);
        assertThat(saved.getEnrollmentId()).isEqualTo(e.getId());
        assertThat(saved.getCourseId()).isEqualTo(course.getId());
        assertThat(saved.getCourseTitle()).isEqualTo("AIDA2 자격 과정");
        // 강사명은 클라이언트가 아니라 서버가 붙인다 (요청에 그런 필드가 없다)
        assertThat(saved.getInstructorName()).isEqualTo("김민지");
        // 수료일 = 마지막 정규 회차 날짜
        assertThat(saved.getCourseCompletedAt()).isEqualTo(roundDate);
    }

    @Test
    @DisplayName("S3: 내 목록은 최근 취득 순이고, 보유가 없으면 200 + _embedded 자체가 없다 (404 아님)")
    void getMine_sortedAndEmpty() throws Exception {
        Account student = account("c-s3@test.com", "diverS3", Role.STUDENT);

        mockMvc.perform(get("/certificates/mine").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());

        register(student, body("FREEDIVING", "AIDA", "LEVEL_1", "OLD", "2023-05-20"));
        register(student, body("SCUBA", "PADI", "LEVEL_2", "NEW", "2025-02-10"));

        MvcResult res = mockMvc.perform(get("/certificates/mine").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("certificates");
        assertThat(list.get(0).get("certificateNumber").asText()).isEqualTo("NEW");
        assertThat(list.get(1).get("certificateNumber").asText()).isEqualTo("OLD");
        // 보유자명은 요청에 없던 값 — 세션 계정에서 파생된다(본인확인 없으면 닉네임)
        assertThat(list.get(0).get("holderName").asText()).isEqualTo("diverS3");
    }

    @Test
    @DisplayName("S4: 사진을 올려 등록하면 저장은 key 로, 조회는 표시용 URL(photoViewUrl)로 내려온다")
    void register_withPhoto() throws Exception {
        Account student = account("c-s4@test.com", "diverS4", Role.STUDENT);
        String fileKey = uploadPhoto(student, "card.JPG");
        // 업로드 응답은 공개 URL 이 아니라 소유자별 저장 참조다
        assertThat(fileKey).contains("studentCertificate/" + student.getId() + "/").endsWith(".jpg");

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "WITH-PHOTO", "2024-11-02");
        payload.put("photoFileKey", fileKey);
        register(student, payload);

        MvcResult res = mockMvc.perform(get("/certificates/mine").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("_embedded").get("certificates").get(0);
        assertThat(item.get("photoViewUrl").asText()).isNotBlank();

        Path onDisk = diskPathOf(fileKey);
        assertThat(Files.exists(onDisk)).isTrue();
    }

    @Test
    @DisplayName("S5: 삭제하면 204 + DB 행과 사진 파일이 함께 사라진다")
    void delete_removesRowAndPhoto() throws Exception {
        Account student = account("c-s5@test.com", "diverS5", Role.STUDENT);
        String fileKey = uploadPhoto(student, "card.jpg");
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "DEL", "2024-11-02");
        payload.put("photoFileKey", fileKey);
        register(student, payload);
        Long id = certificateRepo.findAll().get(0).getId();

        mockMvc.perform(delete("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isNoContent());

        assertThat(certificateRepo.findAll()).isEmpty();
        assertThat(Files.exists(diskPathOf(fileKey))).isFalse(); // 실물 PII 를 고아로 남기지 않는다
    }

    @Test
    @DisplayName("S6: 단건 조회는 상세 진입용 — 표시 URL 을 다시 발급한다 (presigned 3분 만료 대비)")
    void getOne_reissuesViewUrl() throws Exception {
        Account student = account("c-s6@test.com", "diverS6", Role.STUDENT);
        String fileKey = uploadPhoto(student, "card.jpg");
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "ONE", "2024-11-02");
        payload.put("photoFileKey", fileKey);
        register(student, payload);
        Long id = certificateRepo.findAll().get(0).getId();

        mockMvc.perform(get("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateNumber").value("ONE"))
                .andExpect(jsonPath("$.photoViewUrl").isNotEmpty());
    }

    /* ════════════════ V — 검증 거절 ════════════════ */

    @Test
    @DisplayName("V1: 미래 취득일로 등록하면 400 + 어느 필드가 왜 틀렸는지 알려준다")
    void register_rejectsFutureAcquiredAt() throws Exception {
        Account student = account("c-v1@test.com", "diverV1", Role.STUDENT);
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "FUTURE",
                LocalDate.now().plusDays(1).toString());

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("취득일은 오늘보다 미래일 수 없어요."));

        assertThat(certificateRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V2: 카탈로그에 없는 종목 코드로 등록하면 400")
    void register_rejectsUnknownDiscipline() throws Exception {
        Account student = account("c-v2@test.com", "diverV2", Role.STUDENT);

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(body("NOT_A_SPORT", "AIDA", "LEVEL_2", "X", "2024-11-02"))))
                .andExpect(status().isBadRequest());

        assertThat(certificateRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V3: 아직 안 끝난 강의를 연결하면 400 — 진행 중인 수강으로 자격증을 만들 수 없다")
    void register_rejectsIncompleteEnrollment() throws Exception {
        Account instructor = account("c-v3i@test.com", "강사", Role.INSTRUCTOR);
        Account student = account("c-v3@test.com", "diverV3", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, false, LocalDate.now().minusDays(1)); // done 아님

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "X", "2024-11-02");
        payload.put("enrollmentId", e.getId());

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("아직 수강이 끝나지 않은 강의예요."));
    }

    @Test
    @DisplayName("V4: 강의의 종목과 자격증의 종목이 다르면 400 (FE 화면에선 빈 칸으로 보여 통과하던 조합)")
    void register_rejectsDisciplineMismatch() throws Exception {
        Account instructor = account("c-v4i@test.com", "강사", Role.INSTRUCTOR);
        Account student = account("c-v4@test.com", "diverV4", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, true, LocalDate.now().minusDays(3));

        Map<String, Object> payload = body("SCUBA", "PADI", "LEVEL_1", "X", "2024-11-02"); // 강의는 프리다이빙
        payload.put("enrollmentId", e.getId());

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("강의의 종목과 자격증의 종목이 달라요."));
    }

    @Test
    @DisplayName("V5: 남이 올린 사진(photoFileKey)을 자기 자격증에 붙이면 400")
    void register_rejectsOtherPersonsPhoto() throws Exception {
        Account victim = account("c-v5a@test.com", "diverV5a", Role.STUDENT);
        Account attacker = account("c-v5b@test.com", "diverV5b", Role.STUDENT);
        String victimKey = uploadPhoto(victim, "leaked.jpg");

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "X", "2024-11-02");
        payload.put("photoFileKey", victimKey);

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(attacker))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isBadRequest());

        assertThat(certificateRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V6: 이미지가 아닌 파일을 사진으로 올리면 400 (저장하지 않는다)")
    void uploadPhoto_rejectsNonImage() throws Exception {
        Account student = account("c-v6@test.com", "diverV6", Role.STUDENT);
        MockMultipartFile pdf = new MockMultipartFile(
                "image", "card.jpg", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/certificates/photos").file(pdf)
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 비로그인으로 목록·등록·업로드를 부르면 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/certificates/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(write(body("FREEDIVING", "AIDA", "LEVEL_2", "X", "2024-11-02"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/certificates/photos")
                        .file(new MockMultipartFile("image", "a.jpg", MediaType.IMAGE_JPEG_VALUE, "x".getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R2: 남의 자격증은 조회도 삭제도 404 — 존재 자체를 숨긴다 (403 아님)")
    void othersCertificate_404() throws Exception {
        Account owner = account("c-r2a@test.com", "diverR2a", Role.STUDENT);
        Account stranger = account("c-r2b@test.com", "diverR2b", Role.STUDENT);
        register(owner, body("FREEDIVING", "AIDA", "LEVEL_2", "MINE", "2024-11-02"));
        Long id = certificateRepo.findAll().get(0).getId();

        mockMvc.perform(get("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(stranger)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
        mockMvc.perform(delete("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(stranger)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));

        assertThat(certificateRepo.findAll()).hasSize(1); // 남이 못 지운다
    }

    @Test
    @DisplayName("R3: 남의 수강을 연결하려 하면 404 — 남의 enrollment 존재 여부를 알려주지 않는다")
    void othersEnrollment_404() throws Exception {
        Account instructor = account("c-r3i@test.com", "강사", Role.INSTRUCTOR);
        Account owner = account("c-r3a@test.com", "diverR3a", Role.STUDENT);
        Account stranger = account("c-r3b@test.com", "diverR3b", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(owner, course, true, LocalDate.now().minusDays(3));

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "X", "2024-11-02");
        payload.put("enrollmentId", e.getId());

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
    }

    @Test
    @DisplayName("R4: 강사도 개인 자격으로 자격증을 등록할 수 있다 (role 게이트 없음)")
    void instructorCanOwnCertificates() throws Exception {
        Account instructor = account("c-r4@test.com", "강사R4", Role.INSTRUCTOR);

        register(instructor, body("SCUBA", "PADI", "LEVEL_3", "INS-1", "2024-01-10"));

        assertThat(certificateRepo.findAll()).hasSize(1);
    }

    /* ════════════════ A — 탈퇴 파기 ════════════════ */

    @Test
    @DisplayName("A1: 탈퇴 익명화하면 자격증 행과 사진이 함께 파기된다 (실명·자격증번호가 찍힌 PII)")
    void anonymize_purgesCertificatesAndPhotos() throws Exception {
        Account student = account("c-a1@test.com", "diverA1", Role.STUDENT);
        String fileKey = uploadPhoto(student, "card.jpg");
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "PII", "2024-11-02");
        payload.put("photoFileKey", fileKey);
        register(student, payload);

        student.setIsDeleted(true);
        student.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        accountRepo.save(student);

        anonymizationService.anonymize(student.getId());

        assertThat(certificateRepo.findAll()).isEmpty();
        assertThat(Files.exists(diskPathOf(fileKey))).isFalse();
    }

    /** 로컬 저장 참조(서빙 URL) → 디스크 경로. */
    private Path diskPathOf(String storedRef) {
        int at = storedRef.indexOf("/local-uploads/");
        String relative = storedRef.substring(at + "/local-uploads/".length());
        return Paths.get(localDir).toAbsolutePath().normalize().resolve(relative);
    }
}
