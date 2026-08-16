package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.chat.ChatMessage;
import com.diving.pungdong.chat.ChatMessageJpaRepo;
import com.diving.pungdong.chat.ChatMessageKind;
import com.diving.pungdong.chat.ChatParticipantJpaRepo;
import com.diving.pungdong.chat.ChatReadStateJpaRepo;
import com.diving.pungdong.chat.ChatRoomJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 단체 채팅 use-case — 실 H2 + Spring Security 필터 + 실 서비스/JPA.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양.
 * S* = 정상 흐름(방 열기·전송·참여자) / C* = 커서 페이지네이션 / I* = 전송 멱등 /
 * R* = 권한(참여자 아님·결제 전) / X* = 수명(마감·세션 소멸) / U* = unread·읽음 / H* = 회차 카드 노출.
 *
 * <p>모델: 방 키 = 일정(session) id, {@code availability_session} 으로의 FK 없음(전원 환불 시 세션이 물리
 * 삭제돼도 방은 남는다). 참여자 = 강사 + 결제완료(ACCEPT_PENDING/CONFIRMED) 수강생. 방은 지연 생성 —
 * 자격 있는 사람이 처음 열 때 만들어진다.
 *
 * <p>⚠️ {@code Authorization} 헤더는 raw JWT(Bearer prefix 없음).
 * ⚠️ 비참여자·없는 방은 이 레포 관례상 <b>404 가 아니라 400 + code -1009</b> 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired ChatRoomJpaRepo roomRepo;
    @Autowired ChatMessageJpaRepo messageRepo;
    @Autowired ChatParticipantJpaRepo participantRepo;
    @Autowired ChatReadStateJpaRepo readStateRepo;

    private static final LocalDate SLOT_DATE = LocalDate.now().plusWeeks(1);

    @AfterEach
    void clean() {
        readStateRepo.deleteAll();
        messageRepo.deleteAll();
        participantRepo.deleteAll();
        roomRepo.deleteAll();
        enrollmentRepo.deleteAll(); // cascade → rounds
        courseRepo.deleteAll();
        sessionRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
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

    /** 위치 없는 일정 — venue 셋업을 피한다(availability 테스트와 같은 수법). */
    private AvailabilitySession session(Account instructor, LocalTime start, LocalTime end) {
        return sessionRepo.save(AvailabilitySession.builder()
                .instructor(instructor).date(SLOT_DATE).startTime(start).endTime(end)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /**
     * 그 일정에 붙은 회차 1건짜리 수강.
     *
     * <p>⚠️ {@code venueRefId} 를 반드시 채운다. 학생 hub 는 회차에 venueRefId 가 <b>하나도</b> 없으면
     * {@code resolveNames} 가 {@code Map.of()} 를 돌려주고, 거기에 {@code get(null)} 을 하면
     * 불변 맵이 NPE 를 던진다(= 500). 채팅과 무관한 기존 결함이라 여기서는 건드리지 않고 피해 간다
     * (ScheduleHubUseCaseTest 도 같은 이유로 "CUSTOM:1" 을 넣는다).
     */
    private EnrollmentRound enroll(Account student, Course course, AvailabilitySession s, EnrollmentStatus status) {
        EnrollmentRound r = EnrollmentRound.builder()
                .roundIndex(2).roundKind(RoundKind.REGULAR)
                .availabilitySession(s)
                .date(s.getDate()).blockStart(s.getStartTime()).blockEnd(s.getEndTime())
                .venueRefId("CUSTOM:1")
                .status(status).entrySnapshot(0).equipmentSnapshot(0)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        Enrollment e = Enrollment.builder()
                .student(student).course(course).tuitionSnapshot(350000)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        e.addRound(r);
        enrollmentRepo.save(e);
        return r;
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String send(Account sender, Long roomId, String text, String clientMessageId) throws Exception {
        return mockMvc.perform(post("/chat/rooms/" + roomId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(sender))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", text, "clientMessageId", clientMessageId))))
                .andReturn().getResponse().getContentAsString();
    }

    /* ─── S* 정상 흐름 ─────────────────────────────────────── */

    @Test
    @DisplayName("S1 결제완료 수강생이 방을 처음 열면 방이 생기고 강사와 함께 참여자로 들어간다")
    void opensRoomLazily() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        Course c = course(instructor, "AIDA2 프리다이빙 과정");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, c, s, EnrollmentStatus.CONFIRMED);

        assertThat(roomRepo.findById(s.getId())).isEmpty(); // 아직 방이 없다(지연 생성)

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(s.getId()))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.courseTitle").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.roundIndex").value(2))
                .andExpect(jsonPath("$.participantCount").value(2))
                // 강사가 먼저, 표시명은 BE 가 합성한다(FE 가 접미사를 붙이지 않는다)
                .andExpect(jsonPath("$.participants[0].displayName").value("김민지 강사"))
                .andExpect(jsonPath("$.participants[1].displayName").value("김수민 학생"))
                .andExpect(jsonPath("$.closesInSeconds").isNumber());

        assertThat(roomRepo.findById(s.getId())).isPresent();
        assertThat(participantRepo.findByRoomIdAndLeftAtIsNull(s.getId())).hasSize(2);
    }

    @Test
    @DisplayName("S2 방을 열면 안내 시스템 메시지가 한 줄 남는다 — 문구에 날짜를 넣지 않는다(접두는 FE 합성)")
    void writesSystemMessageOnOpen() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk());

        List<ChatMessage> all = messageRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getKind()).isEqualTo(ChatMessageKind.SYSTEM);
        assertThat(all.get(0).getText()).isEqualTo("회차 채팅방이 열렸어요");
        assertThat(all.get(0).getSenderAccountId()).isNull();
    }

    @Test
    @DisplayName("S3 메시지를 보내면 201 이고, 발신자 표시명과 mine=true 가 서버 기준으로 채워진다")
    void sendsMessage() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "선생님 내일 뵐게요", "clientMessageId", "c-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("선생님 내일 뵐게요"))
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.senderDisplayName").value("김수민 학생"))
                .andExpect(jsonPath("$.senderRole").value("STUDENT"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.clientMessageId").value("c-1"));
    }

    @Test
    @DisplayName("S4 상대가 보낸 메시지는 mine=false 다 — 좌우 판정을 클라이언트가 계산하지 않는다")
    void mineIsPerViewer() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        send(student, s.getId(), "안녕하세요", "c-1");

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(status().isOk())
                // [0] 은 방 개설 SYSTEM, [1] 이 학생 메시지
                .andExpect(jsonPath("$.messages[1].mine").value(false))
                .andExpect(jsonPath("$.messages[1].senderDisplayName").value("김수민 학생"));
    }

    @Test
    @DisplayName("S5 참여자 목록은 현재 참여자만 센다")
    void listsParticipants() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/participants")
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(2));
    }

    @Test
    @DisplayName("S6 서로 다른 강의의 수강생이 한 일정에 모여도 헤더는 흔들리지 않는다(먼저 합류한 회차 기준)")
    void headerIsStableAcrossCourses() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account first = account("a@pd.com", "먼저");
        Account second = account("b@pd.com", "나중");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        // 일정은 물리적 (강사,시간,위치) 슬롯이라 다른 강의 수강생이 같은 방에 들어올 수 있다.
        enroll(first, course(instructor, "AIDA2 프리다이빙 과정"), s, EnrollmentStatus.CONFIRMED);
        enroll(second, course(instructor, "PADI 프리다이버 과정"), s, EnrollmentStatus.CONFIRMED);

        // 누가 열든, 몇 번을 열든 같은 제목이어야 한다 — 정렬 없이 findFirst 로 뽑으면 여기서 흔들린다.
        for (Account viewer : List.of(first, second, instructor)) {
            mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.courseTitle").value("AIDA2 프리다이빙 과정"))
                    .andExpect(jsonPath("$.participantCount").value(3));
        }
    }

    /* ─── R* 권한 ──────────────────────────────────────────── */

    @Test
    @DisplayName("R1 결제 전(PENDING) 수강생은 방을 열 수 없다 — 결제 전 = 미생성")
    void unpaidStudentCannotOpen() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.PENDING);

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
        assertThat(roomRepo.findById(s.getId())).isEmpty();
    }

    @Test
    @DisplayName("R2 그 일정과 무관한 사람은 방을 열 수 없다(존재 숨김 -1009)")
    void strangerCannotOpen() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        Account stranger = account("out@pd.com", "남");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(stranger)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(-1009));
    }

    @Test
    @DisplayName("R3 비로그인은 401 이다")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/chat/rooms/1")).andExpect(status().isUnauthorized());
    }

    /* ─── I* 전송 멱등 ─────────────────────────────────────── */

    @Test
    @DisplayName("I1 같은 clientMessageId 로 다시 보내면 새 메시지를 만들지 않고 기존 것을 200 으로 돌려준다")
    void resendIsIdempotent() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "네 알겠습니다", "clientMessageId", "retry-1"))))
                .andExpect(status().isCreated());

        // 응답이 유실돼 사용자가 다시 누른 상황 — 같은 키다.
        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "네 알겠습니다", "clientMessageId", "retry-1"))))
                .andExpect(status().isOk())            // 201 아님 — 새로 만들지 않았다
                .andExpect(jsonPath("$.clientMessageId").value("retry-1"));

        assertThat(messageRepo.findAll().stream()
                .filter(m -> m.getKind() == ChatMessageKind.USER).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("I2 clientMessageId 가 없으면 400 이다 — 멱등키는 필수다")
    void clientMessageIdRequired() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "본문만 보냄"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("I3 연타로 레이트리밋을 넘기면 429 + code -1023 + retryAfterSeconds 를 준다(2xx 로 삼키지 않는다)")
    void rateLimited() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        // 창(10초) 안에서 허용치(10건)까지는 통과한다.
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                            .header(HttpHeaders.AUTHORIZATION, token(student))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("text", "연타 " + i, "clientMessageId", "burst-" + i))))
                    .andExpect(status().isCreated());
        }

        // 11번째는 막힌다. 저장되지 않았으므로 2xx 를 주면 FE 가 성공으로 오해한다.
        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "한 번 더", "clientMessageId", "burst-11"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(-1023))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        assertThat(messageRepo.findAll().stream()
                .filter(m -> m.getKind() == ChatMessageKind.USER).count()).isEqualTo(10);
    }

    /* ─── C* 커서 페이지네이션 ─────────────────────────────── */

    @Test
    @DisplayName("C1 before 는 커서에 '가장 가까운' 과거 N건이다 — 대화 맨 처음으로 점프하지 않는다")
    void beforeReturnsAdjacentPast() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        for (int i = 1; i <= 10; i++) {
            send(student, s.getId(), "메시지 " + i, "c-" + i);
        }
        List<ChatMessage> user = messageRepo.findAll().stream()
                .filter(m -> m.getKind() == ChatMessageKind.USER).sorted((a, b) -> a.getId().compareTo(b.getId()))
                .collect(java.util.stream.Collectors.toList());
        Long cursor = user.get(9).getId();   // 마지막 메시지 id

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .param("before", String.valueOf(cursor)).param("size", "3")
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                // 커서 직전 3건(7,8,9번째) — "메시지 1,2,3" 이 아니다
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[0].text").value("메시지 7"))
                .andExpect(jsonPath("$.messages[2].text").value("메시지 9"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("C2 커서 없이 열면 최신 N건이고, hasMore/nextCursor 는 과거 방향을 가리킨다")
    void initialEntryIsBackward() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        for (int i = 1; i <= 5; i++) {
            send(student, s.getId(), "메시지 " + i, "c-" + i);
        }

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .param("size", "2").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].text").value("메시지 4"))
                .andExpect(jsonPath("$.messages[1].text").value("메시지 5"))
                .andExpect(jsonPath("$.hasMore").value(true));   // 더 과거가 있다
    }

    @Test
    @DisplayName("C3 after 폴링이 빈 목록이면 nextCursor 는 요청 커서를 그대로 에코한다 — 커서가 날아가면 중복 렌더가 난다")
    void emptyPollEchoesCursor() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        send(student, s.getId(), "마지막", "c-1");
        Long latest = messageRepo.findAll().stream().map(ChatMessage::getId)
                .max(Long::compareTo).orElseThrow();

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .param("after", String.valueOf(latest))
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").value(latest));   // null 이 아니다
    }

    @Test
    @DisplayName("C4 before 와 after 를 함께 주면 400 이다")
    void bothCursorsRejected() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        mockMvc.perform(get("/chat/rooms/" + s.getId() + "/messages")
                        .param("before", "5").param("after", "1")
                        .header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isBadRequest());
    }

    /* ─── U* unread / 읽음 ────────────────────────────────── */

    @Test
    @DisplayName("U0 아무도 말하지 않은 새 방의 unread 는 0 이다 — 개설 안내(SYSTEM)는 세지 않는다")
    void systemMessageIsNotUnread() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));

        // 방엔 개설 안내 SYSTEM 1건이 있지만 그건 "읽을 상대 메시지" 가 아니다.
        // 여기서 1 이 나오면 아무도 말 안 한 회차 카드에 빨간 배지가 뜬다.
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        // 회차 카드(학생 hub)도 같은 값이어야 한다 — 배지의 단일 출처.
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.unreadCount").value(0));
    }

    @Test
    @DisplayName("U1 상대 메시지는 unread 로 잡히고, 읽음 처리하면 0 이 된다(내가 보낸 건 세지 않는다)")
    void unreadAndMarkRead() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        send(student, s.getId(), "질문 있어요", "c-1");

        // 강사 시점: 학생 메시지 1건만 센다(개설 SYSTEM 은 제외).
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(jsonPath("$.unreadCount").value(1));

        Long latest = messageRepo.findAll().stream().map(ChatMessage::getId).max(Long::compareTo).orElseThrow();
        mockMvc.perform(patch("/chat/rooms/" + s.getId() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, token(instructor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lastReadMessageId", latest))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(instructor)))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    @DisplayName("U2 읽음 처리는 전진만 한다 — 더 작은 값으로 되감기지 않는다(폴링 경합 방어)")
    void readNeverRewinds() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        send(student, s.getId(), "1", "c-1");
        Long latest = messageRepo.findAll().stream().map(ChatMessage::getId).max(Long::compareTo).orElseThrow();

        mockMvc.perform(patch("/chat/rooms/" + s.getId() + "/read")
                .header(HttpHeaders.AUTHORIZATION, token(instructor))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lastReadMessageId", latest))));
        mockMvc.perform(patch("/chat/rooms/" + s.getId() + "/read")
                .header(HttpHeaders.AUTHORIZATION, token(instructor))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("lastReadMessageId", 1))));

        assertThat(readStateRepo.findByRoomIdAndAccountId(s.getId(), instructor.getId())
                .orElseThrow().getLastReadMessageId()).isEqualTo(latest);
    }

    /* ─── X* 수명 ──────────────────────────────────────────── */

    @Test
    @DisplayName("X1 마감(세션 종료+24h)이 지나면 CLOSED 로 읽히고 전송은 400 이다 — 조회 자체는 200")
    void closedRoomIsReadOnly() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        // 이미 지난 일정 — 마감(+24h)도 지났다
        AvailabilitySession s = sessionRepo.save(AvailabilitySession.builder()
                .instructor(instructor).date(LocalDate.now().minusDays(3))
                .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(17, 0))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);

        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())                       // 읽기는 열려 있다
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.closesInSeconds").doesNotExist());

        mockMvc.perform(post("/chat/rooms/" + s.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "늦었지만", "clientMessageId", "c-late"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X2 전원 환불로 일정이 물리 삭제돼도 방과 대화는 남고 CLOSED 로 읽힌다 — 옛 푸시가 죽지 않는다")
    void survivesSessionHardDelete() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        EnrollmentRound r = enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/chat/rooms/" + s.getId()).header(HttpHeaders.AUTHORIZATION, token(student)));
        send(student, s.getId(), "감사했습니다", "c-1");
        Long roomId = s.getId();

        // SessionCleaner.deleteIfEmpty 와 같은 동작 — 회차의 FK 만 끊고(이력 보존) 일정 행을 지운다.
        List<EnrollmentRound> rounds = roundRepo.findByAvailabilitySessionId(roomId);
        rounds.forEach(rd -> rd.setAvailabilitySession(null));
        roundRepo.saveAll(rounds);
        sessionRepo.deleteById(roomId);

        // FK 가 없으므로 방은 남아 있다.
        assertThat(roomRepo.findById(roomId)).isPresent();
        mockMvc.perform(get("/chat/rooms/" + roomId).header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.courseTitle").value("AIDA2"));   // 스냅샷이라 안 깨진다
    }

    /* ─── H* 회차 카드 노출 ───────────────────────────────── */

    @Test
    @DisplayName("H1 학생 강의일정의 회차 카드에 sessionId 와 chat 이 실린다(결제완료 → ACTIVE)")
    void studentHubCarriesChatState() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.CONFIRMED);

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds[0].sessionId").value(s.getId()))
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.state").value("ACTIVE"))
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.roomId").value(s.getId()))
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.unreadCount").value(0));
    }

    @Test
    @DisplayName("H2 미결제 회차의 chat 은 항상 non-null 이고 HIDDEN + roomId=null 이다 — 아이콘을 숨기는 안전망")
    void unpaidRoundIsHidden() throws Exception {
        Account instructor = account("ins@pd.com", "김민지");
        Account student = account("stu@pd.com", "김수민");
        AvailabilitySession s = session(instructor, LocalTime.of(14, 0), LocalTime.of(17, 0));
        enroll(student, course(instructor, "AIDA2"), s, EnrollmentStatus.PENDING);

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds[0].chat").exists())      // null 이 아니다
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.state").value("HIDDEN"))
                .andExpect(jsonPath("$.courses[0].rounds[0].chat.roomId").doesNotExist());
    }
}
