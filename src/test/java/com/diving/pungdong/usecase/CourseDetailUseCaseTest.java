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
 * <p>그룹: S* 상세 합성, <b>I* 강사 카드 인라인</b>, V* 비공개/없음, <b>C* 마감(CLOSED) 읽기 전용 공개</b>,
 * T* 변경 시각.
 * CUSTOM 위치(이용권 평일/주말 fee 다르게)를 직접 seed 해 입장료 합성을 검증한다.
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
        setStatus(me, id, "OPEN");
        return id;
    }

    /** 강사가 자기 강의의 영업 상태를 바꾼다(PATCH /courses/{id}/status). */
    private void setStatus(Account me, long id, String status) throws Exception {
        mockMvc.perform(patch("/courses/" + id + "/status").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", status))))
                .andExpect(status().isOk());
    }

    /** 코스 작성만 하고 발행하지 않는다 → DRAFT. 한 번도 공개된 적 없는 상태. */
    private long draftCourse(Account me, String venueRefId, String ticketRef) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "임시 과정");
        body.put("kind", "TRIAL");
        body.put("disciplineCode", "FREEDIVING");
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", venueRefId,
                        "tickets", List.of(Map.of("ticketRef", ticketRef, "daypart", "WEEKDAY")))))));
        String loc = mockMvc.perform(post("/courses").header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(loc, "$.id")).longValue();
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
        long id = draftCourse(inst, ref[0], ref[1]);   // 코스 생성만, OPEN 안 함 → DRAFT

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 없는 코스 id 상세는 400")
    void v2_missing_id() throws Exception {
        mockMvc.perform(get("/courses/999999/detail"))
                .andExpect(status().isBadRequest());
    }

    /* ════════ C — 마감(CLOSED) 강의: 읽기는 열고 행동은 막는다 (BE #322) ════════ */

    /**
     * 이 네 개는 <b>한 묶음</b>이다 — 되돌리려면 함께 봐야 한다. 읽기 축과 행동 축을 나눈 게
     * 이 피처의 전부라, C2 나 C3 하나만 빠져도 분리가 조용히 무너진다.
     */
    @Test
    @DisplayName("C1 OPEN 이었다가 마감된 강의 상세는 그대로 200 이고 status=CLOSED 로 온다 (색인 자산이 안 죽는다)")
    void c1_closed_detail_still_readable() throws Exception {
        Account inst = account("c1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);
        setStatus(inst, id, "CLOSED");

        mockMvc.perform(get("/courses/" + id + "/detail"))  // 비로그인
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                // 본문은 그대로다 — 마감은 CTA 만 바꾸지 내용을 감추지 않는다
                .andExpect(jsonPath("$.title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.venues[0].name").value("잠실 잠수풀"));
    }

    @Test
    @DisplayName("C2 한 번도 공개된 적 없는(DRAFT→CLOSED 직행) 강의 상세는 여전히 400 — 판정은 상태가 아니라 발행 이력")
    void c2_closed_but_never_published_hidden() throws Exception {
        Account inst = account("c2@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = draftCourse(inst, ref[0], ref[1]);
        setStatus(inst, id, "CLOSED");   // OPEN 을 거치지 않았다 = 지킬 색인 자산이 없다

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C3 마감된 강의는 읽을 수 있지만 저장(북마크)은 400 — 읽기와 행동은 다른 축이다")
    void c3_closed_readable_but_not_bookmarkable() throws Exception {
        Account inst = account("c3@pungdong.com");
        Account student = account("c3s@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);
        setStatus(inst, id, "CLOSED");

        mockMvc.perform(get("/courses/" + id + "/detail")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/courses/" + id + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C4 다시 OPEN 하면 같은 URL 이 그대로 되살아난다 (발행 이력은 되돌리지 않는다)")
    void c4_reopen_restores_same_url() throws Exception {
        Account inst = account("c4@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);
        setStatus(inst, id, "CLOSED");
        setStatus(inst, id, "OPEN");

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    /* ════════ T — 변경 시각(sitemap lastmod, BE #323) ════════ */

    @Test
    @DisplayName("T1 상세에 createdAt·updatedAt 이 항상 온다 — 웹이 lastmod 를 낼 수 있어야 한다")
    void t1_detail_has_timestamps() throws Exception {
        Account inst = account("t1@pungdong.com");
        String[] ref = seedVenueWithTicket(inst, 48000, 55000);
        long id = openCourse(inst, ref[0], ref[1]);

        mockMvc.perform(get("/courses/" + id + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                // 발행(OPEN 전이)도 변경이다 — 한 번도 수정 안 한 강의라고 null 이면 안 된다.
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }
}
