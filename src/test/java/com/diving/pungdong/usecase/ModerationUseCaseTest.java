package com.diving.pungdong.usecase;

import com.diving.pungdong.account.*;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.chat.ChatMessageJpaRepo;
import com.diving.pungdong.chat.ChatParticipantJpaRepo;
import com.diving.pungdong.chat.ChatReadStateJpaRepo;
import com.diving.pungdong.chat.ChatRoomJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.*;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.moderation.ContentReportJpaRepo;
import com.diving.pungdong.moderation.ReportStatus;
import com.diving.pungdong.moderation.ReportTargetType;
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

import java.time.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고 — <b>커뮤니티 밖으로 넓어진 대상</b>(강의·채팅 메시지)과 어드민 큐.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * {@code R*} 강의 신고 / {@code M*} 채팅 메시지 신고 / {@code Q*} 어드민 큐 / {@code G*} 가드.
 *
 * <p>게시물·댓글 신고(X*)는 {@code CommunityUseCaseTest} 에 그대로 있다 — 커뮤니티에서 태어난 규칙이라
 * 그쪽에 두는 게 읽기 좋다. 여기는 <b>대상이 늘면서 새로 생긴 규칙</b>만 담는다.
 *
 * <p>이 피처에서 가장 위험한 건 두 가지다.
 * <b>①</b> 조치했다는데 대상이 살아 있는 것(이 레포는 "조치 = 실제로 숨긴다" 를 불변식으로 지킨다) —
 * {@code R2}·{@code M2} 가 막는다.
 * <b>②</b> 방에 없는 사람이 메시지를 신고해 어드민 큐의 미리보기로 남의 대화를 읽는 것(IDOR) —
 * {@code G2} 가 막는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModerationUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired ContentReportJpaRepo reportRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired ChatRoomJpaRepo roomRepo;
    @Autowired ChatMessageJpaRepo messageRepo;
    @Autowired ChatParticipantJpaRepo participantRepo;
    @Autowired ChatReadStateJpaRepo readStateRepo;

    private static final LocalDate SLOT_DATE = LocalDate.now().plusWeeks(1);

    @AfterEach
    void clean() {
        reportRepo.deleteAll();
        readStateRepo.deleteAll();
        messageRepo.deleteAll();
        participantRepo.deleteAll();
        roomRepo.deleteAll();
        enrollmentRepo.deleteAll();
        courseRepo.deleteAll();
        sessionRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ── fixture ─────────────────────────────────────────── */

    private Account account(String email, String nick, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).isDeleted(false).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private Course course(Account instructor, String title) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title(title)
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .levels(new HashSet<>(Set.of(CertLevel.LEVEL_2)))
                .totalRounds(1).price(350000).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private AvailabilitySession session(Account instructor) {
        return sessionRepo.save(AvailabilitySession.builder()
                .instructor(instructor).date(SLOT_DATE)
                .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(17, 0))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /** 결제완료 회차 — 채팅방 참여 자격의 근거다(방은 자격자가 처음 열 때 생긴다). */
    private void enroll(Account student, Course c, AvailabilitySession s) {
        EnrollmentRound r = EnrollmentRound.builder()
                .roundIndex(2).roundKind(RoundKind.REGULAR)
                .availabilitySession(s)
                .date(s.getDate()).blockStart(s.getStartTime()).blockEnd(s.getEndTime())
                .venueRefId("CUSTOM:1")
                .status(EnrollmentStatus.CONFIRMED).entrySnapshot(0).equipmentSnapshot(0)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        Enrollment e = Enrollment.builder()
                .student(student).course(c).tuitionSnapshot(350000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r);
        enrollmentRepo.save(e);
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 방을 열고(지연 생성) 메시지 하나를 보낸 뒤 그 id 를 돌려준다. */
    private long sendMessage(Account sender, Long roomId, String text) throws Exception {
        mockMvc.perform(get("/chat/rooms/" + roomId).header(HttpHeaders.AUTHORIZATION, token(sender)))
                .andExpect(status().isOk());
        MvcResult result = mockMvc.perform(post("/chat/rooms/" + roomId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", text, "clientMessageId", "cid-" + text.hashCode()))))
                // 전송은 201 이다(멱등 재전송이면 200) — 커서 채팅의 계약.
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void report(Account reporter, String targetType, long targetId) throws Exception {
        mockMvc.perform(post("/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId
                                + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isOk());
    }

    private void actionLatest(Account admin) throws Exception {
        Long reportId = reportRepo.findAll().get(0).getId();
        mockMvc.perform(patch("/admin/reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIONED\"}"))
                .andExpect(status().isOk());
    }

    /* ════════════════ R — 강의 신고 ════════════════ */

    @Test
    @DisplayName("R1: 남의 강의를 신고하면 '강의' 항목으로 큐에 쌓인다")
    void courseReport_isQueuedAsCourse() throws Exception {
        Account instructor = account("r1i@c.com", "coachR1", Role.INSTRUCTOR);
        Account student = account("r1s@c.com", "diverR1", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        report(student, "COURSE", c.getId());

        assertThat(reportRepo.count()).isEqualTo(1);
        assertThat(reportRepo.findAll().get(0).getTargetType()).isEqualTo(ReportTargetType.COURSE);
    }

    @Test
    @DisplayName("R2: 조치하면 강의가 둘러보기·공개 상세에서 실제로 사라진다 (상태만 바뀌면 조치가 아니다)")
    void courseAction_actuallyHidesCourse() throws Exception {
        Account admin = account("r2a@c.com", "adminR2", Role.ADMIN);
        Account instructor = account("r2i@c.com", "coachR2", Role.INSTRUCTOR);
        Account student = account("r2s@c.com", "diverR2", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        mockMvc.perform(get("/courses/browse?disciplineCode=FREEDIVING"))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        report(student, "COURSE", c.getId());
        actionLatest(admin);

        mockMvc.perform(get("/courses/browse?disciplineCode=FREEDIVING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // 둘러보기에서만 빼면 상세 URL 이 우회로가 된다.
        mockMvc.perform(get("/courses/" + c.getId() + "/detail"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R3: 조치된 강의는 새 수강신청을 받지 않는다 (슬롯 피커도 같은 조건을 본다)")
    void blockedCourse_rejectsNewEnrollment() throws Exception {
        Account admin = account("r3a@c.com", "adminR3", Role.ADMIN);
        Account instructor = account("r3i@c.com", "coachR3", Role.INSTRUCTOR);
        Account student = account("r3s@c.com", "diverR3", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        report(student, "COURSE", c.getId());
        actionLatest(admin);

        // 고를 수 있는 슬롯이 보이는데 제출만 400 이면 최악이라 둘이 같은 조건을 봐야 한다.
        mockMvc.perform(get("/enrollments/options?courseId=" + c.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R4: 조치해도 이미 확정된 수강은 그대로다 (조치는 거래를 끊지 않는다)")
    void blockedCourse_keepsConfirmedEnrollment() throws Exception {
        Account admin = account("r4a@c.com", "adminR4", Role.ADMIN);
        Account instructor = account("r4i@c.com", "coachR4", Role.INSTRUCTOR);
        Account student = account("r4s@c.com", "diverR4", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        enroll(student, c, session(instructor));

        report(student, "COURSE", c.getId());
        actionLatest(admin);

        // 수강 목록이 살아 있어야 한다 — 돈이 오간 관계를 조치가 일방적으로 끊으면 환불·분쟁이 된다.
        mockMvc.perform(get("/enrollments/mine").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk());
        assertThat(enrollmentRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("R5: 강사는 자기 강의를 신고할 수 없다")
    void selfCourseReport_rejected() throws Exception {
        Account instructor = account("r5i@c.com", "coachR5", Role.INSTRUCTOR);
        Course c = course(instructor, "문섬 어드밴스드");

        mockMvc.perform(post("/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"COURSE\",\"targetId\":" + c.getId()
                                + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ M — 채팅 메시지 신고 ════════════════ */

    @Test
    @DisplayName("M1: 같은 방 참여자는 상대 메시지를 신고할 수 있다")
    void chatMessageReport_byParticipant() throws Exception {
        Account instructor = account("m1i@c.com", "coachM1", Role.INSTRUCTOR);
        Account student = account("m1s@c.com", "diverM1", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        AvailabilitySession s = session(instructor);
        enroll(student, c, s);

        long messageId = sendMessage(instructor, s.getId(), "부적절한 말");
        report(student, "CHAT_MESSAGE", messageId);

        assertThat(reportRepo.findAll().get(0).getTargetType()).isEqualTo(ReportTargetType.CHAT_MESSAGE);
    }

    @Test
    @DisplayName("M2: 조치하면 메시지가 툼스톤으로 바뀐다 (자리는 남기고 내용만 지운다)")
    void chatMessageAction_replacesWithTombstone() throws Exception {
        Account admin = account("m2a@c.com", "adminM2", Role.ADMIN);
        Account instructor = account("m2i@c.com", "coachM2", Role.INSTRUCTOR);
        Account student = account("m2s@c.com", "diverM2", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        AvailabilitySession s = session(instructor);
        enroll(student, c, s);

        long messageId = sendMessage(instructor, s.getId(), "부적절한 말");
        report(student, "CHAT_MESSAGE", messageId);
        actionLatest(admin);

        // 물리 삭제하지 않는다 — 대화 흐름이 끊기고 커서 페이지네이션의 id 연속성도 깨진다.
        // (방을 열 때 들어간 SYSTEM 안내 메시지가 앞에 있어 인덱스가 아니라 id 로 찾는다.)
        assertThat(messageRepo.findById(messageId)).get()
                .extracting(com.diving.pungdong.chat.ChatMessage::isDeleted).isEqualTo(true);
        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[?(@.id == " + messageId + ")].text")
                        .value(org.hamcrest.Matchers.contains("삭제된 메시지입니다.")))
                .andExpect(jsonPath("$.messages[?(@.id == " + messageId + ")].deleted")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    /* ════════════════ G — 가드 ════════════════ */

    @Test
    @DisplayName("G1: 자기가 보낸 메시지는 신고할 수 없다")
    void selfChatMessageReport_rejected() throws Exception {
        Account instructor = account("g1i@c.com", "coachG1", Role.INSTRUCTOR);
        Account student = account("g1s@c.com", "diverG1", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        AvailabilitySession s = session(instructor);
        enroll(student, c, s);

        long messageId = sendMessage(instructor, s.getId(), "내 메시지");

        mockMvc.perform(post("/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"CHAT_MESSAGE\",\"targetId\":" + messageId
                                + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("G2: 그 방에 없는 사람은 메시지를 신고할 수 없다 (미리보기가 남의 대화를 읽는 채널이 된다)")
    void outsiderCannotReportChatMessage() throws Exception {
        Account instructor = account("g2i@c.com", "coachG2", Role.INSTRUCTOR);
        Account student = account("g2s@c.com", "diverG2", Role.STUDENT);
        Account outsider = account("g2o@c.com", "diverG2o", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        AvailabilitySession s = session(instructor);
        enroll(student, c, s);

        long messageId = sendMessage(instructor, s.getId(), "우리끼리 대화");

        mockMvc.perform(post("/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"CHAT_MESSAGE\",\"targetId\":" + messageId
                                + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isBadRequest());
        assertThat(reportRepo.count()).isZero();
    }

    /* ════════════════ Q — 어드민 큐 ════════════════ */

    @Test
    @DisplayName("Q1: 큐를 항목(targetType)으로 나눠 볼 수 있다 · 대상 작성자 닉네임이 함께 실린다")
    void adminQueue_filtersByTargetTypeAndShowsAuthor() throws Exception {
        Account admin = account("q1a@c.com", "adminQ1", Role.ADMIN);
        Account instructor = account("q1i@c.com", "coachQ1", Role.INSTRUCTOR);
        Account student = account("q1s@c.com", "diverQ1", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        report(student, "COURSE", c.getId());

        mockMvc.perform(get("/admin/reports?targetType=COURSE")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                // 어드민 FE 는 targetType + targetId 로 상품 페이지 링크를 조립한다(BE 는 URL 을 안 만든다).
                .andExpect(jsonPath("$._embedded.reports[0].targetId").value(c.getId()))
                .andExpect(jsonPath("$._embedded.reports[0].targetAuthorNickName").value("coachQ1"))
                .andExpect(jsonPath("$._embedded.reports[0].targetPreview").value("문섬 어드밴스드"));

        mockMvc.perform(get("/admin/reports?targetType=POST")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("Q2: 신고 큐는 ADMIN 만 볼 수 있다")
    void adminQueue_requiresAdmin() throws Exception {
        Account student = account("q2s@c.com", "diverQ2", Role.STUDENT);

        mockMvc.perform(get("/admin/reports").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Q3: 구 경로(/community/reports)도 당분간 같이 받는다 (FE 가 옮길 때까지)")
    void legacyPathStillAccepted() throws Exception {
        Account instructor = account("q3i@c.com", "coachQ3", Role.INSTRUCTOR);
        Account student = account("q3s@c.com", "diverQ3", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        mockMvc.perform(post("/community/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"COURSE\",\"targetId\":" + c.getId()
                                + ",\"reason\":\"SPAM\"}"))
                .andExpect(status().isOk());
        assertThat(reportRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Q4: 같은 강사의 다른 강의에 걸린 신고가 한 사람의 누적으로 합산돼 보인다")
    void adminQueue_aggregatesReportsPerTargetAuthor() throws Exception {
        Account admin = account("q4a@c.com", "adminQ4", Role.ADMIN);
        Account instructor = account("q4i@c.com", "coachQ4", Role.INSTRUCTOR);
        Account other = account("q4o@c.com", "coachQ4o", Role.INSTRUCTOR);
        Account student = account("q4s@c.com", "diverQ4", Role.STUDENT);
        Course first = course(instructor, "문섬 어드밴스드");
        Course second = course(instructor, "범섬 베이직");
        Course unrelated = course(other, "섶섬 트립");

        report(student, "COURSE", first.getId());
        report(student, "COURSE", second.getId());
        report(student, "COURSE", unrelated.getId());

        // 강의별로 흩어져 있으면 "1건씩 세 건" 으로 보여 어디서도 안 걸린다 — 사람 축으로 합산한다.
        mockMvc.perform(get("/admin/reports?targetType=COURSE")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.reports[?(@.targetAuthorNickName == 'coachQ4')]"
                        + ".targetAuthorReportCount")
                        .value(org.hamcrest.Matchers.contains(2, 2)))
                .andExpect(jsonPath("$._embedded.reports[?(@.targetAuthorNickName == 'coachQ4o')]"
                        + ".targetAuthorReportCount")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    @DisplayName("Q5: 대상이 지워져도 '누구에 대한 신고인지' 는 남는다 (접수 시점에 고정한다)")
    void adminQueue_keepsTargetAuthorAfterTargetIsGone() throws Exception {
        Account admin = account("q5a@c.com", "adminQ5", Role.ADMIN);
        Account instructor = account("q5i@c.com", "coachQ5", Role.INSTRUCTOR);
        Account student = account("q5s@c.com", "diverQ5", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        report(student, "COURSE", c.getId());
        courseRepo.deleteById(c.getId());

        // 대상을 열어 알아내는 방식이면 여기서 작성자가 사라지고, 그 행은 영영 판단할 수 없게 된다.
        mockMvc.perform(get("/admin/reports")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.reports[0].targetAuthorNickName").value("coachQ5"))
                // 본문 미리보기는 대상과 함께 사라지는 게 맞다(그건 대상에만 있는 정보다).
                .andExpect(jsonPath("$._embedded.reports[0].targetPreview").doesNotExist());
    }

    @Test
    @DisplayName("Q6: 작성자 닉네임으로 그 사람에 대한 신고만 모아 볼 수 있다 (없는 닉네임은 빈 페이지)")
    void adminQueue_filtersByTargetAuthorNickName() throws Exception {
        Account admin = account("q6a@c.com", "adminQ6", Role.ADMIN);
        Account instructor = account("q6i@c.com", "coachQ6", Role.INSTRUCTOR);
        Account other = account("q6o@c.com", "coachQ6o", Role.INSTRUCTOR);
        Account student = account("q6s@c.com", "diverQ6", Role.STUDENT);
        report(student, "COURSE", course(instructor, "문섬 어드밴스드").getId());
        report(student, "COURSE", course(other, "섶섬 트립").getId());

        mockMvc.perform(get("/admin/reports?targetAuthorNickName=coachQ6")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.reports[0].targetAuthorNickName").value("coachQ6"));

        // 오타는 어드민 화면을 깨는 사건이 아니다 — 400 이 아니라 빈 결과다.
        mockMvc.perform(get("/admin/reports?targetAuthorNickName=없는사람")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("Q7: 강의 신고에는 신고자가 그 강의를 신청한 적 있는지가 함께 실린다")
    void adminQueue_showsWhetherReporterEnrolled() throws Exception {
        Account admin = account("q7a@c.com", "adminQ7", Role.ADMIN);
        Account instructor = account("q7i@c.com", "coachQ7", Role.INSTRUCTOR);
        Account studentInside = account("q7s@c.com", "diverQ7", Role.STUDENT);
        Account outsider = account("q7o@c.com", "diverQ7o", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");
        enroll(studentInside, c, session(instructor));

        report(studentInside, "COURSE", c.getId());
        report(outsider, "COURSE", c.getId());

        // "수강한 적 없는 사람의 신고" 와 "실제 수강생의 분쟁" 은 완전히 다른 사건인데
        // 신고 본문만으로는 구분이 안 된다.
        mockMvc.perform(get("/admin/reports?targetType=COURSE")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.reports[?(@.reporterNickName == 'diverQ7')]"
                        + ".reporterEnrolled").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$._embedded.reports[?(@.reporterNickName == 'diverQ7o')]"
                        + ".reporterEnrolled").value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    @DisplayName("Q8: 기각에도 처리 메모가 남는다 ('따로 경고함' 과 '문제없음' 이 같은 행이면 이력이 거짓이 된다)")
    void adminHandle_keepsNoteOnDismissal() throws Exception {
        Account admin = account("q8a@c.com", "adminQ8", Role.ADMIN);
        Account instructor = account("q8i@c.com", "coachQ8", Role.INSTRUCTOR);
        Account student = account("q8s@c.com", "diverQ8", Role.STUDENT);
        Course c = course(instructor, "문섬 어드밴스드");

        report(student, "COURSE", c.getId());
        Long reportId = reportRepo.findAll().get(0).getId();

        mockMvc.perform(patch("/admin/reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISMISSED\",\"note\":\"강사에게 경고 전달, 강의는 유지\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminNote").value("강사에게 경고 전달, 강의는 유지"));

        // 강의는 살아 있어야 한다 — 기각을 고른 이유가 그것이다(조치는 사실상 판매 중단이다).
        assertThat(courseRepo.findById(c.getId())).get()
                .extracting(Course::getBlockedAt).isNull();
        assertThat(reportRepo.findById(reportId)).get()
                .extracting(com.diving.pungdong.moderation.ContentReport::getAdminNote)
                .isEqualTo("강사에게 경고 전달, 강의는 유지");
    }
}
