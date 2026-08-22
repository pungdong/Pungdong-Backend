package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.CertificateVerification;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueDaypart;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueTicket;
import com.diving.pungdong.venue.VenueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 강의 상세(GET /courses/{id}/detail) use-case = 실행 가능한 사양. 둘러보기 카드 → 상세(OPEN 누구나).
 * 강사용 GET /courses/{id} 와 달리 venue 를 합성: 위치명·<b>입장료(이용권×평일/주말 daypart fee)</b>·장비.
 * {@code @DisplayName} 위→아래로 읽으면 규칙.
 *
 * <p>그룹: S* 상세 합성, <b>I* 강사 카드 인라인</b>, V* 비공개/없음. CUSTOM 위치(이용권 평일/주말 fee
 * 다르게)를 직접 seed 해 입장료 합성을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseDetailUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired StudentCertificateJpaRepo certificateRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;

    @AfterEach
    void cleanUp() {
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        certificateRepo.deleteAll();
        applicationRepo.deleteAll();
        brandingRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private Account account(String email) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(email.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    /** 승인된 강사 신청 + 자격증 1건 — 인증마크(isInstructor)·certs 의 출처. 두 번 불러도 1건. */
    private void approveAsInstructor(Account account, String disciplineCode, String organizationCode) {
        if (applicationRepo.findByAccountIdAndDisciplineCode(account.getId(), disciplineCode).isPresent()) {
            return;
        }
        InstructorApplication application = InstructorApplication.builder()
                .account(account)
                .disciplineCode(disciplineCode)
                .status(InstructorApplicationStatus.APPROVED)
                .reviewedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        applicationRepo.save(application);
        verifiedCertificate(account, disciplineCode, organizationCode);
    }

    /** VERIFIED 강사레벨 자격증 1장 — 인증마크(certs·organizationCodes)의 출처(2026-08-22 수렴: 승인 신청 첨부 → VERIFIED 자격증). */
    private StudentCertificate verifiedCertificate(Account owner, String disciplineCode, String organizationCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(organizationCode)
                .organizationName(organizationCode).level(CertLevel.INSTRUCTOR)
                .certificateNumber("INS-1").acquiredAt(java.time.LocalDate.of(2020, 1, 1))
                .source(CertificateSource.EXTERNAL).photoFileKey("studentCertificate/" + owner.getId() + "/x.jpg")
                .createdAt(now)
                .verification(new CertificateVerification(CertificateVerificationStatus.VERIFIED,
                        CertificateVerificationKind.APPLICATION, null, now, now))
                .build());
    }


    /** 이용권 1개(평일 fee / 주말 fee 다르게)를 가진 CUSTOM 위치 seed. returns [venueRefId, ticketRef]. */
    private String[] seedVenueWithTicket(Account owner, int weekdayFee, int weekendFee) {
        VenueTicket t = VenueTicket.builder()
                .name("일반권 (3시간)").sortOrder(0)
                .disciplineCodes(new HashSet<>(Set.of("FREEDIVING"))).build();
        t.addDaypart(VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(weekdayFee).timeMode(TimeMode.FIXED).build());
        t.addDaypart(VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(weekendFee).timeMode(TimeMode.SAME).build());
        Venue v = Venue.builder()
                .owner(owner).name("잠실 잠수풀").type(VenueType.SWIMMING_POOL)
                .address("서울특별시 송파구 올림픽로 25").lockedDisciplineCode("FREEDIVING")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        v.addTicket(t);
        venueRepo.save(v);
        return new String[]{"CUSTOM:" + v.getId(), t.getRef()};
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    /**
     * 코스 작성(POST) → OPEN 전이. 그 위치(venueRefId)에서 이용권(ticketRef)을 평일에 쓴다.
     *
     * <p><b>강사 승인을 먼저 심는다</b> — 발행(OPEN)은 그 종목의 정식 강사만 할 수 있다.
     * 이 코스들은 전부 FREEDIVING 이다.
     */
    private long openCourse(Account me, String venueRefId, String ticketRef) throws Exception {
        approveAsInstructor(me, "FREEDIVING", "AIDA");
        Map<String, Object> body = new HashMap<>();
        body.put("title", "AIDA2 프리다이빙 과정");
        body.put("kind", "CERTIFICATION");
        body.put("organizationCode", "AIDA");
        body.put("levels", List.of("LEVEL_2"));
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 350000);
        body.put("totalRounds", 1);
        body.put("description", "자유잠수 L2 과정");
        body.put("rounds", List.of(Map.of("description", "1회차 적응",
                "venues", List.of(Map.of("venueRefId", venueRefId,
                        "tickets", List.of(Map.of("ticketRef", ticketRef, "daypart", "WEEKDAY")))))));
        String loc = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(loc, "$.id")).longValue();
        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isOk());
        return id;
    }

    /* ════════════════ S — 상세 합성 ════════════════ */

    @Test
    @DisplayName("S1 OPEN 코스 공개 상세는 비로그인으로 정체성·강사·회차가 온다")
    void s1_public_detail() throws Exception {
        Account inst = account("s1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))  // 인증 헤더 없음
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.price").value(350000))
                .andExpect(jsonPath("$.instructorName").value("s1"))
                .andExpect(jsonPath("$.rounds[0].roundIndex").value(1))
                .andExpect(jsonPath("$.rounds[0].venueRefIds[0]").value(ref[0]));
    }

    @Test
    @DisplayName("S2 입장료 합성 — 위치 이용권의 평일/주말 fee 가 daypart 별로 정확히 온다(단일 entry 아님)")
    void s2_entry_fee_by_daypart() throws Exception {
        Account inst = account("s2@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venues[0].name").value("잠실 잠수풀"))
                .andExpect(jsonPath("$.venues[0].area").value(startsWith("서울")))
                .andExpect(jsonPath("$.venues[0].tickets[0].ticketName").value("일반권 (3시간)"))
                // 평일 48,000 / 주말 55,000 — daypart 별 fee
                .andExpect(jsonPath("$.venues[0].tickets[0].fees[?(@.daypart=='WEEKDAY')].fee").value(hasItem(48000)))
                .andExpect(jsonPath("$.venues[0].tickets[0].fees[?(@.daypart=='WEEKEND')].fee").value(hasItem(55000)));
    }

    /* ════════════════ I — 강사 카드 인라인 ════════════════ */

    @Test
    @DisplayName("I1 브랜딩 프로필을 만든 적 없어도 강사 카드는 온다 — 아바타·닉네임은 브랜딩 소유가 아니다")
    void i1_instructor_card_without_branding() throws Exception {
        Account inst = account("i1@pungdong.com");
        inst.setProfilePhoto(profilePhotoRepo.save(ProfilePhoto.builder()
                .imageUrl("https://cdn.example.com/profile-photo/i1.png").build()));
        accountRepo.save(inst);
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructor.nickName").value("i1"))
                .andExpect(jsonPath("$.instructor.avatarUrl").value("https://cdn.example.com/profile-photo/i1.png"))
                // 프로필 미작성 → 이 둘만 빈다. 아바타가 같이 사라지지 않는 게 요점.
                .andExpect(jsonPath("$.instructor.tagline").doesNotExist())
                .andExpect(jsonPath("$.instructor.bio").doesNotExist());
    }

    @Test
    @DisplayName("I2 승인 전 강사는 강의를 OPEN 할 수 없다 — 그래서 상세에 심사 중인 강사가 뜰 일이 없다")
    void i2_pending_instructor_cannotPublish() throws Exception {
        Account inst = account("i2@pungdong.com");   // 강사 신청 자체가 없는 계정
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);

        // 코스 작성까지는 된다 — 검수를 기다리는 동안 준비하는 건 허용된 동선이다.
        Map<String, Object> body = new HashMap<>();
        body.put("title", "심사 중 과정");
        body.put("kind", "TRIAL");
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", ref[0],
                        "tickets", List.of(Map.of("ticketRef", ref[1], "daypart", "WEEKDAY")))))));
        String created = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        // 발행만 막힌다. 여기서 조용히 성공시키면 강사는 "왜 아무도 안 들어오지" 를 알 수 없다.
        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest());

        // 상세도 당연히 안 열린다(DRAFT).
        mockMvc.perform(get("/courses/" + id + "/detail")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("I2-1 다른 종목만 승인받았으면 그 종목 강의도 OPEN 할 수 없다 (승인은 종목별)")
    void i2_1_approvedForAnotherDiscipline_cannotPublish() throws Exception {
        Account inst = account("i21@pungdong.com");
        approveAsInstructor(inst, "SCUBA", "PADI");   // 스쿠버만 승인
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);

        Map<String, Object> body = new HashMap<>();
        body.put("title", "프리다이빙 과정");
        body.put("kind", "TRIAL");
        body.put("disciplineCode", "FREEDIVING");    // 승인받지 않은 종목
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", ref[0],
                        "tickets", List.of(Map.of("ticketRef", ref[1], "daypart", "WEEKDAY")))))));
        String created = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("I3 승인된 강사는 인증마크·자격 뱃지·공개 강의 수가 함께 온다")
    void i3_approved_instructor_card() throws Exception {
        Account inst = account("i3@pungdong.com");
        approveAsInstructor(inst, "FREEDIVING", "AIDA");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructor.isInstructor").value(true))
                // 원시 boolean 의 Jackson 함정 — 키가 둘로 갈라지면 안 된다.
                .andExpect(jsonPath("$.instructor.instructor").doesNotExist())
                .andExpect(jsonPath("$.instructor.certs[0].disciplineCode").value("FREEDIVING"))
                .andExpect(jsonPath("$.instructor.certs[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.instructor.lessonCount").value(1));
    }

    @Test
    @DisplayName("I4 프로필에 적은 한마디(tagline)·자기소개(bio)가 상세에 실린다 — 두 번째 호출이 필요 없다")
    void i4_tagline_and_bio_inlined() throws Exception {
        Account inst = account("i4@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);
        mockMvc.perform(patch("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"숨 참고 시작해요\",\"bio\":\"AIDA 강사입니다\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructor.tagline").value("숨 참고 시작해요"))
                .andExpect(jsonPath("$.instructor.bio").value("AIDA 강사입니다"));
    }

    @Test
    @DisplayName("I5 프로필을 비공개로 내리면 한마디·자기소개만 빠지고, 강사 카드 자체는 남는다")
    void i5_unpublished_profile_hidesBlurbOnly() throws Exception {
        Account inst = account("i5@pungdong.com");
        approveAsInstructor(inst, "FREEDIVING", "AIDA");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);
        mockMvc.perform(patch("/branding/me").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagline\":\"한마디\",\"bio\":\"자기소개\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/branding/me/publish").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}"))
                .andExpect(status().isOk());

        // 공개 프로필은 닫히고
        mockMvc.perform(get("/instructors/i5")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                // 포트폴리오 본문은 함께 감춰진다 — 비공개의 뜻이 "내 포트폴리오를 감춘다" 라서.
                // 에러가 아니라 값만 빈다: 키는 있고 값이 명시적 null 이다(FE 가 "안 온 것" 과 구분 불필요).
                .andExpect(jsonPath("$.instructor.tagline").value(nullValue()))
                .andExpect(jsonPath("$.instructor.bio").value(nullValue()))
                // 계정 사실·자격은 브랜딩 소유가 아니라 그대로 남는다 — 카드가 통째로 사라지면 안 된다.
                .andExpect(jsonPath("$.instructor.nickName").value("i5"))
                .andExpect(jsonPath("$.instructor.isInstructor").value(true))
                .andExpect(jsonPath("$.instructor.certs[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.instructor.lessonCount").value(1));
    }

    @Test
    @DisplayName("I6 사진을 올린 적 없으면 avatarUrl 은 null — 공유 기본 이미지 문자열이 새지 않는다")
    void i6_default_photo_folded_to_null() throws Exception {
        Account inst = account("i6@pungdong.com");
        inst.setProfilePhoto(profilePhotoRepo.save(ProfilePhoto.builder()
                .imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build()));
        accountRepo.save(inst);
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instructor.avatarUrl").doesNotExist());
    }

    /* ════════════════ V — 비공개 / 없음 ════════════════ */

    @Test
    @DisplayName("V1 DRAFT(미공개) 코스 상세는 400 (존재 숨김)")
    void v1_draft_hidden() throws Exception {
        Account inst = account("v1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        // 코스 생성만, OPEN 안 함 → DRAFT
        Map<String, Object> body = new HashMap<>();
        body.put("title", "임시 과정");
        body.put("kind", "TRIAL");
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", ref[0],
                        "tickets", List.of(Map.of("ticketRef", ref[1], "daypart", "WEEKDAY")))))));
        String loc = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(loc, "$.id")).longValue();

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 없는 코스 id 상세는 400")
    void v2_missing_id() throws Exception {
        mockMvc.perform(get("/courses/999999/detail"))
                .andExpect(status().isBadRequest());
    }
}
