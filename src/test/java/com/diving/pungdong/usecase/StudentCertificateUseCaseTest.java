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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 보유 자격증 use-case — 조회 / 등록(외부·강의연결) / 수정 / 삭제 / 사진 업로드.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 성공 / V* 검증거절 / R* 권한 / A* 탈퇴 파기.
 *
 * <p>사진은 <b>필수</b>라(2026-08-16) {@code register} 헬퍼가 payload 에 {@code photoFileKey} 가 없으면
 * 실제 업로드를 태워 붙인다 — 사진이 주제가 아닌 시나리오의 잡음을 줄인다. 사진 유무 자체를 검증하는
 * 시나리오(V11·V12·V13)는 헬퍼를 우회해 직접 {@code perform} 한다.
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

    /**
     * 등록. <b>사진이 필수</b>라, payload 에 {@code photoFileKey} 가 없으면 실제 업로드를 태워 붙인다 —
     * 사진 자체가 주제가 아닌 시나리오(정렬·스냅샷·권한 등)에서 픽스처 잡음을 줄이려는 것이다.
     * 사진 유무를 검증하는 시나리오는 이 헬퍼를 쓰지 않고 직접 {@code perform} 한다.
     */
    private String register(Account who, Map<String, Object> payload) throws Exception {
        Map<String, Object> body = new HashMap<>(payload); // 호출자 맵을 건드리지 않는다
        if (!body.containsKey("photoFileKey")) {
            body.put("photoFileKey", uploadPhoto(who, "fixture.jpg"));
        }
        return mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(who))
                        .contentType(MediaType.APPLICATION_JSON).content(write(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * 사진 없이 저장된 <b>옛 행</b> — 사진이 필수가 되기 전 데이터. API 로는 더 이상 만들 수 없어
     * repo 로 직접 넣는다(검증 대상이 등록이 아니라 그 행의 수정 동작이다).
     */
    private StudentCertificate legacyCertificateWithoutPhoto(Account owner) {
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner)
                .disciplineCode("FREEDIVING")
                .organizationCode("AIDA")
                .level(CertLevel.LEVEL_2)
                .certificateNumber("LEGACY-NO-PHOTO")
                .acquiredAt(LocalDate.of(2023, 5, 20))
                .source(CertificateSource.EXTERNAL)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    /** 수정(PUT) — 전면 교체. 사진은 payload 에 photoFileKey 가 없으면 기존 것이 유지된다. */
    private String update(Account who, Long id, Map<String, Object> payload) throws Exception {
        return mockMvc.perform(put("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(who))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                .andExpect(status().isOk())
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

    @Test
    @DisplayName("S7: 정규 회차를 다 끝낸 뒤 추가세션을 잡아도 자격증은 등록된다 (카드는 PROGRESS 로 돌아가지만 자격은 취득했다)")
    void register_allowedWhileExtraRoundInProgress() throws Exception {
        Account instructor = account("c-s7i@test.com", "강사S7", Role.INSTRUCTOR);
        Account student = account("c-s7@test.com", "diverS7", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, true, LocalDate.now().minusDays(5));

        // 정규를 다 끝낸 뒤 추가세션(EXTRA)을 잡은 상태 — 미결제라 hub 카드는 PROGRESS 로 되돌아간다.
        e.addRound(EnrollmentRound.builder()
                .roundKind(RoundKind.EXTRA).date(LocalDate.now().plusDays(3))
                .status(EnrollmentStatus.PENDING)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        enrollmentRepo.save(e);

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "EXTRA-OK", "2024-11-02");
        payload.put("enrollmentId", e.getId());
        register(student, payload);

        // hub 표시 상태(PROGRESS)로 판정했다면 여기서 400 이 났을 것이다.
        // hub 가 같은 값을 certifiable 로 노출하는지는 ScheduleHubUseCaseTest SH6 이 검증한다.
        assertThat(certificateRepo.findAll().get(0).getSource()).isEqualTo(CertificateSource.PUNGDONG);
    }

    @Test
    @DisplayName("S8: 선택 필드를 생략하든 명시적 null 로 보내든 같게 받는다 (FE 매퍼 구현에 안 묶인다)")
    void register_toleratesOmittedAndExplicitNull() throws Exception {
        Account student = account("c-s8@test.com", "diverS8", Role.STUDENT);

        // (1) 선택 필드 생략
        Map<String, Object> omitted = new HashMap<>();
        omitted.put("disciplineCode", "FREEDIVING");
        omitted.put("organizationCode", "AIDA");
        omitted.put("level", "LEVEL_1");
        omitted.put("certificateNumber", "OMITTED");
        omitted.put("acquiredAt", "2024-11-02");
        register(student, omitted);

        // (2) 같은 필드를 명시적 null 로
        Map<String, Object> explicitNull = new HashMap<>(omitted);
        explicitNull.put("certificateNumber", "NULLED");
        // photoFileKey 는 이제 필수라 이 목록에 없다 — 명시적 null 은 400 이 정상이다(V11).
        explicitNull.put("photoFileKey", uploadPhoto(student, "card.jpg"));
        for (String k : List.of("organizationName", "organizationFullName",
                "certificationDisplayName", "issuer", "enrollmentId")) {
            explicitNull.put(k, null);
        }
        register(student, explicitNull);

        assertThat(certificateRepo.findAll()).hasSize(2)
                .allSatisfy(c -> assertThat(c.getSource()).isEqualTo(CertificateSource.EXTERNAL));
    }

    @Test
    @DisplayName("S9: enrollmentId 를 문자열 \"318\" 로 보내도 숫자로 받는다 (JS 직렬화 편차 흡수)")
    void register_acceptsStringEnrollmentId() throws Exception {
        Account instructor = account("c-s9i@test.com", "강사S9", Role.INSTRUCTOR);
        Account student = account("c-s9@test.com", "diverS9", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, true, LocalDate.now().minusDays(2));

        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "STR-ID", "2024-11-02");
        payload.put("enrollmentId", String.valueOf(e.getId())); // 숫자가 아니라 문자열

        register(student, payload);

        assertThat(certificateRepo.findAll().get(0).getEnrollmentId()).isEqualTo(e.getId());
    }

    @Test
    @DisplayName("S10: 표시명 스냅샷은 슬래시·악센트·괄호·한글이 섞여도 그대로 왕복한다 (Sanity 실데이터)")
    void register_preservesDisplayNamesWithSpecialCharacters() throws Exception {
        Account student = account("c-s10@test.com", "diverS10", Role.STUDENT);

        // ⚠️ 지어낸 값이 아니다 — certs-mobile 이 Sanity CDN 을 쳐서 뽑은 실측 최장값이다.
        //    organizationCode 에는 @Pattern 을 걸었지만 **표시명 3종엔 걸면 안 된다**:
        //    걸면 CMAS(악센트+괄호+한글)와 SDI 프리다이빙 자격(슬래시)이 통째로 400 이 된다.
        String fullName = "Confédération Mondiale des Activités Subaquatiques (세계수중연맹)";
        String certName = "Basic Freediver / Pool Freediver";

        Map<String, Object> payload = body("FREEDIVING", "CMAS", "LEVEL_1", "CMAS-1", "2024-11-02");
        payload.put("organizationName", "CMAS");
        payload.put("organizationFullName", fullName);
        payload.put("certificationDisplayName", certName);
        register(student, payload);

        StudentCertificate saved = certificateRepo.findAll().get(0);
        assertThat(saved.getOrganizationFullName()).isEqualTo(fullName);
        assertThat(saved.getCertificationDisplayName()).isEqualTo(certName);

        // 응답까지 온전히 나가는지(직렬화·charset 포함) 확인 — 저장만 되고 깨져 나가면 소용없다.
        MvcResult res = mockMvc.perform(get("/certificates/mine").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = objectMapper.readTree(
                        res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("_embedded").get("certificates").get(0);
        assertThat(item.get("organizationFullName").asText()).isEqualTo(fullName);
        assertThat(item.get("certificationDisplayName").asText()).isEqualTo(certName);
    }

    @Test
    @DisplayName("S11: 수정하면 번호·취득일·단체 표시명이 바뀌고, 사진을 안 보내면 기존 사진이 그대로 유지된다")
    void update_replacesScalarsAndKeepsPhotoWhenAbsent() throws Exception {
        Account student = account("c-s11@test.com", "diverS11", Role.STUDENT);
        String fileKey = uploadPhoto(student, "card.jpg");
        Map<String, Object> created = body("FREEDIVING", "AIDA", "LEVEL_2", "TYPO-1", "2024-11-02");
        created.put("photoFileKey", fileKey);
        created.put("issuer", "원래 발급처");
        register(student, created);
        Long id = certificateRepo.findAll().get(0).getId();

        // 오타 정정 — 번호·취득일·표시명만 고치고 사진은 다시 올리지 않는다(폼이 재업로드를 강요하면 안 된다).
        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "AIDA-2024-99999", "2024-12-25");
        edited.put("organizationName", "AIDA KOREA");
        update(student, id, edited);

        StudentCertificate saved = certificateRepo.findById(id).orElseThrow();
        assertThat(saved.getCertificateNumber()).isEqualTo("AIDA-2024-99999");
        assertThat(saved.getAcquiredAt()).isEqualTo(LocalDate.of(2024, 12, 25));
        assertThat(saved.getOrganizationName()).isEqualTo("AIDA KOREA");
        // 사진은 유지 — key 도 실물 파일도 그대로다
        assertThat(saved.getPhotoFileKey()).isEqualTo(fileKey);
        assertThat(Files.exists(diskPathOf(fileKey))).isTrue();
        // 전면 교체라 보내지 않은 스칼라는 비워진다(PATCH 가 아니다)
        assertThat(saved.getIssuer()).isNull();
    }

    @Test
    @DisplayName("S12: 새 사진 key 로 수정하면 사진이 교체되고 이전 사진 실물이 파기된다 (PII 고아 방지)")
    void update_replacesPhotoAndPurgesOldObject() throws Exception {
        Account student = account("c-s12@test.com", "diverS12", Role.STUDENT);
        String oldKey = uploadPhoto(student, "old.jpg");
        Map<String, Object> created = body("FREEDIVING", "AIDA", "LEVEL_2", "PHOTO", "2024-11-02");
        created.put("photoFileKey", oldKey);
        register(student, created);
        Long id = certificateRepo.findAll().get(0).getId();

        String newKey = uploadPhoto(student, "new.jpg");
        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "PHOTO", "2024-11-02");
        edited.put("photoFileKey", newKey);
        update(student, id, edited);

        assertThat(certificateRepo.findById(id).orElseThrow().getPhotoFileKey()).isEqualTo(newKey);
        assertThat(Files.exists(diskPathOf(newKey))).isTrue();
        assertThat(Files.exists(diskPathOf(oldKey))).isFalse(); // 옛 카드 사진이 남아 있으면 안 된다
    }

    @Test
    @DisplayName("S13: 외부 취득으로 등록한 뒤 강의를 연결하면 source=PUNGDONG + 강사·강의가 박제된다 (깜빡한 연동 사후 보정)")
    void update_linksCourseAfterwards() throws Exception {
        Account instructor = account("c-s13i@test.com", "김민지", Role.INSTRUCTOR);
        Account student = account("c-s13@test.com", "diverS13", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        LocalDate roundDate = LocalDate.now().minusDays(4);
        Enrollment e = enrollment(student, course, true, roundDate);

        register(student, body("FREEDIVING", "AIDA", "LEVEL_2", "LATE-LINK", "2024-11-02"));
        Long id = certificateRepo.findAll().get(0).getId();
        assertThat(certificateRepo.findById(id).orElseThrow().getSource()).isEqualTo(CertificateSource.EXTERNAL);

        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "LATE-LINK", "2024-11-02");
        edited.put("enrollmentId", e.getId());
        update(student, id, edited);

        StudentCertificate saved = certificateRepo.findById(id).orElseThrow();
        assertThat(saved.getSource()).isEqualTo(CertificateSource.PUNGDONG);
        assertThat(saved.getEnrollmentId()).isEqualTo(e.getId());
        assertThat(saved.getCourseId()).isEqualTo(course.getId());
        assertThat(saved.getCourseTitle()).isEqualTo("AIDA2 자격 과정");
        assertThat(saved.getInstructorName()).isEqualTo("김민지"); // 요청에 없던 값 — 서버가 붙인다
        assertThat(saved.getCourseCompletedAt()).isEqualTo(roundDate);
    }

    @Test
    @DisplayName("S14: enrollmentId 를 빼고 수정하면 연결이 해제된다 — source=EXTERNAL + 강의 스냅샷이 전부 비워진다")
    void update_unlinksCourse() throws Exception {
        Account instructor = account("c-s14i@test.com", "강사S14", Role.INSTRUCTOR);
        Account student = account("c-s14@test.com", "diverS14", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, true, LocalDate.now().minusDays(6));

        Map<String, Object> created = body("FREEDIVING", "AIDA", "LEVEL_2", "UNLINK", "2024-11-02");
        created.put("enrollmentId", e.getId());
        register(student, created);
        Long id = certificateRepo.findAll().get(0).getId();

        // 잘못 연결했다 — enrollmentId 없이 다시 보내면 해제(전면 교체 의미론)
        update(student, id, body("FREEDIVING", "AIDA", "LEVEL_2", "UNLINK", "2024-11-02"));

        StudentCertificate saved = certificateRepo.findById(id).orElseThrow();
        assertThat(saved.getSource()).isEqualTo(CertificateSource.EXTERNAL);
        // 부분 잔존은 유령 강의를 만든다 — 전부 비워져야 한다
        assertThat(saved.getEnrollmentId()).isNull();
        assertThat(saved.getCourseId()).isNull();
        assertThat(saved.getCourseTitle()).isNull();
        assertThat(saved.getCourseCompletedAt()).isNull();
        assertThat(saved.getInstructorName()).isNull();
    }

    @Test
    @DisplayName("S15: 사진 없이 등록됐던 옛 행도 사진을 붙여 수정하면 200 — 막다른 길이 되지 않는다")
    void update_legacyRowWithNewPhotoSucceeds() throws Exception {
        Account student = account("c-s15@test.com", "diverS15", Role.STUDENT);
        StudentCertificate legacy = legacyCertificateWithoutPhoto(student);
        String newKey = uploadPhoto(student, "attached.jpg");

        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "NOW-WITH-PHOTO", "2024-11-02");
        edited.put("photoFileKey", newKey);
        update(student, legacy.getId(), edited);

        StudentCertificate saved = certificateRepo.findById(legacy.getId()).orElseThrow();
        assertThat(saved.getPhotoFileKey()).isEqualTo(newKey);
        assertThat(saved.getCertificateNumber()).isEqualTo("NOW-WITH-PHOTO");
    }

    /* ════════════════ V — 검증 거절 ════════════════ */

    @Test
    @DisplayName("V1: 미래 취득일로 등록하면 400 + 어느 필드가 왜 틀렸는지 알려준다")
    void register_rejectsFutureAcquiredAt() throws Exception {
        Account student = account("c-v1@test.com", "diverV1", Role.STUDENT);
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "FUTURE",
                LocalDate.now().plusDays(1).toString());
        payload.put("photoFileKey", uploadPhoto(student, "card.jpg")); // 사진은 필수 — 여기서 걸리면 안 된다

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
        Map<String, Object> payload = body("NOT_A_SPORT", "AIDA", "LEVEL_2", "X", "2024-11-02");
        payload.put("photoFileKey", uploadPhoto(student, "card.jpg")); // 종목에서 걸려야지 사진에서 걸리면 안 된다

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
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
        payload.put("photoFileKey", uploadPhoto(student, "card.jpg"));

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
        payload.put("photoFileKey", uploadPhoto(student, "card.jpg"));

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
    @DisplayName("V7: 저장 참조에 `..` 이 섞인 photoFileKey 는 400 — 경로 이탈로 남의 파일을 지울 수 없다")
    void register_rejectsPathTraversalPhotoKey() throws Exception {
        Account student = account("c-v7@test.com", "diverV7", Role.STUDENT);
        Map<String, Object> payload = body("FREEDIVING", "AIDA", "LEVEL_2", "TRAVERSAL", "2024-11-02");
        // prefix 는 내 것이지만 `..` 로 baseDir 를 벗어난다 — contains 검사만으론 통과하던 형태.
        payload.put("photoFileKey",
                "http://localhost:8080/local-uploads/studentCertificate/" + student.getId() + "/../../../etc/passwd");

        mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
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

    @Test
    @DisplayName("V8: 수정에 남이 올린 사진 key 를 넣으면 400 — 기존 사진은 그대로 남는다")
    void update_rejectsOtherPersonsPhoto() throws Exception {
        Account victim = account("c-v8a@test.com", "diverV8a", Role.STUDENT);
        Account attacker = account("c-v8b@test.com", "diverV8b", Role.STUDENT);
        String victimKey = uploadPhoto(victim, "leaked.jpg");
        String myKey = uploadPhoto(attacker, "mine.jpg");

        Map<String, Object> created = body("FREEDIVING", "AIDA", "LEVEL_2", "V8", "2024-11-02");
        created.put("photoFileKey", myKey);
        register(attacker, created);
        Long id = certificateRepo.findAll().get(0).getId();

        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "V8", "2024-11-02");
        edited.put("photoFileKey", victimKey);

        mockMvc.perform(put("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(attacker))
                        .contentType(MediaType.APPLICATION_JSON).content(write(edited)))
                .andExpect(status().isBadRequest());

        assertThat(certificateRepo.findById(id).orElseThrow().getPhotoFileKey()).isEqualTo(myKey);
        assertThat(Files.exists(diskPathOf(victimKey))).isTrue(); // 남의 실물도 건드리지 않는다
    }

    @Test
    @DisplayName("V9: 수정으로 아직 안 끝난 강의를 연결하면 400 — 등록과 같은 판정을 쓴다")
    void update_rejectsIncompleteEnrollment() throws Exception {
        Account instructor = account("c-v9i@test.com", "강사V9", Role.INSTRUCTOR);
        Account student = account("c-v9@test.com", "diverV9", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, false, LocalDate.now().minusDays(1)); // done 아님

        register(student, body("FREEDIVING", "AIDA", "LEVEL_2", "V9", "2024-11-02"));
        Long id = certificateRepo.findAll().get(0).getId();

        Map<String, Object> edited = body("FREEDIVING", "AIDA", "LEVEL_2", "V9-EDITED", "2024-11-02");
        edited.put("enrollmentId", e.getId());

        mockMvc.perform(put("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(edited)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("아직 수강이 끝나지 않은 강의예요."));

        // 거절됐으면 스칼라 변경도 남으면 안 된다(부분 적용 금지 — 통째로 롤백)
        assertThat(certificateRepo.findById(id).orElseThrow().getCertificateNumber()).isEqualTo("V9");
    }

    @Test
    @DisplayName("V10: 수정에서 강의의 종목과 자격증의 종목이 다르면 400")
    void update_rejectsDisciplineMismatch() throws Exception {
        Account instructor = account("c-v10i@test.com", "강사V10", Role.INSTRUCTOR);
        Account student = account("c-v10@test.com", "diverV10", Role.STUDENT);
        Course course = course(instructor, "FREEDIVING", "AIDA");
        Enrollment e = enrollment(student, course, true, LocalDate.now().minusDays(3));

        register(student, body("SCUBA", "PADI", "LEVEL_1", "V10", "2024-11-02"));
        Long id = certificateRepo.findAll().get(0).getId();

        Map<String, Object> edited = body("SCUBA", "PADI", "LEVEL_1", "V10", "2024-11-02"); // 강의는 프리다이빙
        edited.put("enrollmentId", e.getId());

        mockMvc.perform(put("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON).content(write(edited)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("강의의 종목과 자격증의 종목이 달라요."));
    }

    @Test
    @DisplayName("V11: 사진 없이 등록하면 400 — 사진은 필수다 (\"사진이 진실\": 수영장 입장 시 제시하는 게 사진이다)")
    void register_rejectsMissingPhoto() throws Exception {
        Account student = account("c-v11@test.com", "diverV11", Role.STUDENT);

        // 생략도, 명시적 null 도, 빈 문자열도 전부 같은 이유로 거절된다.
        Map<String, Object> omitted = body("FREEDIVING", "AIDA", "LEVEL_2", "NO-PHOTO", "2024-11-02");
        Map<String, Object> explicitNull = body("FREEDIVING", "AIDA", "LEVEL_2", "NO-PHOTO", "2024-11-02");
        explicitNull.put("photoFileKey", null);
        Map<String, Object> blank = body("FREEDIVING", "AIDA", "LEVEL_2", "NO-PHOTO", "2024-11-02");
        blank.put("photoFileKey", "   ");

        for (Map<String, Object> payload : List.of(omitted, explicitNull, blank)) {
            mockMvc.perform(post("/certificates").header(HttpHeaders.AUTHORIZATION, token(student))
                            .contentType(MediaType.APPLICATION_JSON).content(write(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.msg").value("자격증 사진을 추가해주세요."));
        }

        assertThat(certificateRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V12: 사진 없이 등록됐던 옛 행을 새 사진 없이 수정하면 400 — 결과가 사진 없는 자격증이면 안 된다")
    void update_rejectsWhenResultWouldHaveNoPhoto() throws Exception {
        Account student = account("c-v12@test.com", "diverV12", Role.STUDENT);
        StudentCertificate legacy = legacyCertificateWithoutPhoto(student);

        // photoFileKey 를 안 보내는 건 "유지"인데, 유지할 사진이 없다.
        mockMvc.perform(put("/certificates/" + legacy.getId()).header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(body("FREEDIVING", "AIDA", "LEVEL_2", "STILL-NO-PHOTO", "2024-11-02"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("자격증 사진을 추가해주세요."));

        // 거절됐으면 스칼라 변경도 남으면 안 된다
        assertThat(certificateRepo.findById(legacy.getId()).orElseThrow().getCertificateNumber())
                .isEqualTo("LEGACY-NO-PHOTO");
    }

    @Test
    @DisplayName("V13: 사진 없는 옛 행도 조회·삭제는 그대로 된다 — DB 제약을 안 걸었기에 읽다가 막히지 않는다")
    void legacyRowWithoutPhotoStaysReadableAndDeletable() throws Exception {
        Account student = account("c-v13@test.com", "diverV13", Role.STUDENT);
        StudentCertificate legacy = legacyCertificateWithoutPhoto(student);

        mockMvc.perform(get("/certificates/" + legacy.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoViewUrl").isEmpty()); // 사진 없으면 null (생략이 아니라 명시적 null)

        mockMvc.perform(delete("/certificates/" + legacy.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isNoContent());

        assertThat(certificateRepo.findAll()).isEmpty();
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
        payload.put("photoFileKey", uploadPhoto(stranger, "card.jpg"));

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

    @Test
    @DisplayName("R5: 남의 자격증은 수정도 404 — 존재를 숨기고 내용도 그대로다")
    void updateOthersCertificate_404() throws Exception {
        Account owner = account("c-r5a@test.com", "diverR5a", Role.STUDENT);
        Account stranger = account("c-r5b@test.com", "diverR5b", Role.STUDENT);
        register(owner, body("FREEDIVING", "AIDA", "LEVEL_2", "MINE", "2024-11-02"));
        Long id = certificateRepo.findAll().get(0).getId();

        mockMvc.perform(put("/certificates/" + id).header(HttpHeaders.AUTHORIZATION, token(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(body("FREEDIVING", "AIDA", "LEVEL_3", "HIJACKED", "2024-11-02"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));

        assertThat(certificateRepo.findById(id).orElseThrow().getCertificateNumber()).isEqualTo("MINE");
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
