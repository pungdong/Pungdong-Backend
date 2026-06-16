package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityWindow;
import com.diving.pungdong.availability.AvailabilityWindowJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.venue.*;
import com.diving.pungdong.venue.equipment.VenueEquipmentExtension;
import com.diving.pungdong.venue.equipment.VenueEquipmentExtensionJpaRepo;
import com.diving.pungdong.venue.equipment.VenueEquipmentItem;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강신청(enrollment/booking) use-case 시나리오 — 실 H2 + Spring Security 필터 체인 + 실제 서비스/JPA.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위→아래로 = 사양. 그룹 — O* 옵션 교집합 / S* 신청 / J* 합류
 * (exact-match) / F* 만석 / A* 강사 수락·거절 / C* 취소 / G*·R* 게이트·권한.
 *
 * <p>핵심: 슬롯 = 강사 availability window ∩ venue 운영블록. 첫 신청이 window 를 (venue,블록)으로 bind →
 * 같은 venue·정확히 같은 블록만 합류(부분겹침 불가). 신청은 PENDING(결제 없음) → 강사 수락 시 CONFIRMED.
 * availability 캘린더에 pending/confirmed/applicants 가 실제로 반영된다. ⚠️ Authorization = raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnrollmentUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AvailabilityWindowJpaRepo windowRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired VenueEquipmentExtensionJpaRepo equipmentRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalTime B_START = LocalTime.of(14, 0);
    private static final LocalTime B_END = LocalTime.of(17, 0);

    @AfterEach
    void cleanUp() {
        enrollmentRepo.deleteAll();
        windowRepo.deleteAll();
        courseRepo.deleteAll();
        equipmentRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─────────────────────────────────────────── */

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private void enterInstructorTrack(Account a) {
        applicationRepo.save(InstructorApplication.builder()
                .account(a).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
    }

    /** 잠실풀 — 일반권 1개, 평일·주말 FIXED 블록 09–12·14–17(둘 다 fee 15,000 → 날짜 무관 결정적). */
    private Venue saveVenue(Account owner) {
        VenueTimeBlock b1 = VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build();
        VenueTimeBlock b2 = VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build();
        VenueTimeBlock b1w = VenueTimeBlock.builder().startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).sortOrder(0).build();
        VenueTimeBlock b2w = VenueTimeBlock.builder().startTime(B_START).endTime(B_END).sortOrder(1).build();
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(b1); weekday.addTimeBlock(b2);
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(b1w); weekend.addTimeBlock(b2w);
        VenueTicket ticket = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday); ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(LocalDateTime.now()).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    private String ticketRefOf(Venue v) {
        return v.getTickets().get(0).getRef();
    }

    private String venueRefOf(Venue v) {
        return VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
    }

    private VenueEquipmentItem saveEquipment(Account owner, String venueRefId) {
        VenueEquipmentItem item = VenueEquipmentItem.builder().name("롱핀").price(5000).sortOrder(0).build();
        VenueEquipmentExtension ext = VenueEquipmentExtension.builder()
                .owner(owner).venueRefId(venueRefId).createdAt(LocalDateTime.now()).build();
        ext.addItem(item);
        equipmentRepo.save(ext);
        return item;
    }

    private Course saveCourse(Account instructor, String venueRefId, String ticketRef) {
        Course course = Course.builder().instructor(instructor).title("AIDA2 프리다이빙 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(1).price(350000).status(CourseStatus.OPEN).createdAt(LocalDateTime.now()).build();
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build();
        RoundVenue rv = RoundVenue.builder().venueRefId(venueRefId).sortOrder(0).build();
        rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(rv);
        course.addRound(round);
        return courseRepo.save(course);
    }

    private AvailabilityWindow saveWindow(Account instructor, LocalDate date, LocalTime start, LocalTime end, int cap) {
        // cap 을 일정 override 로 고정 — 유효정원이 계정 기본값과 무관하게 cap 이 되어 만석/잔여 시나리오가 결정적.
        return windowRepo.save(AvailabilityWindow.builder()
                .instructor(instructor).date(date).startTime(start).endTime(end).capacityOverride(cap)
                .createdAt(LocalDateTime.now()).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EnrollmentCreateRequest req(Long courseId, Long windowId, String venueRef, String ticketRef,
                                        List<String> equip) {
        return EnrollmentCreateRequest.builder()
                .courseId(courseId).availabilityWindowId(windowId)
                .venueRefId(venueRef).ticketRef(ticketRef)
                .blockStart(B_START).blockEnd(B_END).equipmentRefs(equip).build();
    }

    /** 강사·venue·course·window 한 세트(09–18, 정원 cap) 준비. 인덱스 [course, window]. */
    private Object[] setup(Account instructor, int cap) {
        Venue venue = saveVenue(instructor);
        String venueRef = venueRefOf(venue);
        Course course = saveCourse(instructor, venueRef, ticketRefOf(venue));
        AvailabilityWindow w = saveWindow(instructor, D1, LocalTime.of(9, 0), LocalTime.of(18, 0), cap);
        return new Object[]{course, w, venue, venueRef};
    }

    /* ─── O* 옵션 교집합 ───────────────────────────────────── */

    @Test
    @DisplayName("O1 신청 옵션은 강사 가용시간 ∩ venue 운영블록 ∩ 코스 1회차 위치 교집합 슬롯을 준다")
    void optionsIntersection() throws Exception {
        Account ins = account("ins1@pd.com", "강사1");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        Account stu = account("stu1@pd.com", "수강생1");

        mockMvc.perform(get("/enrollments/options")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .param("courseId", String.valueOf(course.getId())))
                .andExpect(status().isOk())
                // 09–18 window 안에 09–12·14–17 두 블록 → 슬롯 2개
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].capacity").value(4))
                .andExpect(jsonPath("$.slots[0].remaining").value(4))
                .andExpect(jsonPath("$.slots[0].entryFee").value(15000))
                .andExpect(jsonPath("$.course.price").value(350000));
    }

    @Test
    @DisplayName("O2 venue 블록이 강사 가용시간에 안 들어가면(부분만 겹침) 슬롯에서 빠진다")
    void optionsExcludesBlocksOutsideWindow() throws Exception {
        Account ins = account("ins2@pd.com", "강사2");
        enterInstructorTrack(ins);
        Venue venue = saveVenue(ins);
        Course course = saveCourse(ins, venueRefOf(venue), ticketRefOf(venue));
        // 가용시간 14–17 → 09–12 블록은 안 들어감, 14–17 만 ⊆
        saveWindow(ins, D1, B_START, B_END, 4);
        Account stu = account("stu2@pd.com", "수강생2");

        mockMvc.perform(get("/enrollments/options")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .param("courseId", String.valueOf(course.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(1));
    }

    /* ─── S* 신청 ──────────────────────────────────────────── */

    @Test
    @DisplayName("S1 학생이 신청하면 PENDING 이 생기고 가용시간 캘린더에 대기 1명·신청자가 반영된다")
    void submitPendingReflectsOnCalendar() throws Exception {
        Account ins = account("ins3@pd.com", "강사3");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        String venueRef = (String) s[3];
        Account stu = account("stu3@pd.com", "수강생3");

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), w.getId(), venueRef, ticketRefOf((Venue) s[2]), List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.tuition").value(350000))
                .andExpect(jsonPath("$.entry").value(15000))
                .andExpect(jsonPath("$.total").value(365000));

        assertThat(enrollmentRepo.findAll()).hasSize(1);
        assertThat(enrollmentRepo.findAll().get(0).getStatus()).isEqualTo(EnrollmentStatus.PENDING);

        // 강사 가용시간 캘린더: 그 window 가 pending 1 + 신청자 반영, venue bound
        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.windows[0].pendingCount").value(1))
                .andExpect(jsonPath("$._embedded.windows[0].status").value("PENDING"))
                .andExpect(jsonPath("$._embedded.windows[0].applicants[0].name").value("수강생3"))
                .andExpect(jsonPath("$._embedded.windows[0].venueName").value("잠실 잠수풀장"));
    }

    @Test
    @DisplayName("S2 장비를 함께 신청하면 장비 가격이 스냅샷되고 총액에 합산된다")
    void submitWithEquipment() throws Exception {
        Account ins = account("ins4@pd.com", "강사4");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        String venueRef = (String) s[3];
        VenueEquipmentItem fin = saveEquipment(ins, venueRef);
        Account stu = account("stu4@pd.com", "수강생4");

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), w.getId(), venueRef, ticketRefOf((Venue) s[2]),
                        List.of(String.valueOf(fin.getId()))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentTotal").value(5000))
                .andExpect(jsonPath("$.total").value(370000))
                .andExpect(jsonPath("$.equipment[0].name").value("롱핀"));
    }

    /* ─── J* 합류(exact-match) ─────────────────────────────── */

    @Test
    @DisplayName("J1 같은 window·같은 venue·정확히 같은 블록이면 둘째 학생도 합류(둘 다 PENDING)")
    void joinSameSlot() throws Exception {
        Account ins = account("ins5@pd.com", "강사5");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        String venueRef = (String) s[3];
        String ticketRef = ticketRefOf((Venue) s[2]);

        submitOk(account("a@pd.com", "학생A"), course, w, venueRef, ticketRef);
        submitOk(account("b@pd.com", "학생B"), course, w, venueRef, ticketRef);

        assertThat(enrollmentRepo.findByAvailabilityWindowIdAndStatusIn(
                w.getId(), List.of(EnrollmentStatus.PENDING))).hasSize(2);
    }

    @Test
    @DisplayName("J2 이미 14–17 로 찬 window 에 09–12(다른 블록)로 신청하면 거절(부분겹침·다른 세션 불가)")
    void rejectDifferentBlockOnBoundWindow() throws Exception {
        Account ins = account("ins6@pd.com", "강사6");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        String venueRef = (String) s[3];
        String ticketRef = ticketRefOf((Venue) s[2]);
        submitOk(account("a2@pd.com", "학생A2"), course, w, venueRef, ticketRef); // bind 14–17

        EnrollmentCreateRequest other = EnrollmentCreateRequest.builder()
                .courseId(course.getId()).availabilityWindowId(w.getId())
                .venueRefId(venueRef).ticketRef(ticketRef)
                .blockStart(LocalTime.of(9, 0)).blockEnd(LocalTime.of(12, 0)).build();
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account("b2@pd.com", "학생B2")))
                .contentType(MediaType.APPLICATION_JSON).content(json(other)))
                .andExpect(status().isBadRequest());
    }

    /* ─── F* 만석 ──────────────────────────────────────────── */

    @Test
    @DisplayName("F1 정원이 확정으로 다 차면(만석) 새 신청은 400")
    void fullRejectsNewApplication() throws Exception {
        Account ins = account("ins7@pd.com", "강사7");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 1); // 정원 1
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        String venueRef = (String) s[3];
        String ticketRef = ticketRefOf((Venue) s[2]);

        Enrollment first = submitOk(account("c@pd.com", "학생C"), course, w, venueRef, ticketRef);
        // 강사 수락 → CONFIRMED, 정원(1) 도달
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", first.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account("d@pd.com", "학생D")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), w.getId(), venueRef, ticketRef, List.of()))))
                .andExpect(status().isBadRequest());
    }

    /* ─── A* 강사 수락·거절 ────────────────────────────────── */

    @Test
    @DisplayName("A1 강사가 수락하면 CONFIRMED 가 되고 캘린더에 확정 1명으로 반영된다")
    void acceptConfirms() throws Exception {
        Account ins = account("ins8@pd.com", "강사8");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        Enrollment e = submitOk(account("e@pd.com", "학생E"), course, w, (String) s[3], ticketRefOf((Venue) s[2]));

        mockMvc.perform(post("/instructor/enrollments/{id}/accept", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk());

        assertThat(enrollmentRepo.findById(e.getId()).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.CONFIRMED);
        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(jsonPath("$._embedded.windows[0].confirmedCount").value(1))
                .andExpect(jsonPath("$._embedded.windows[0].status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("A2 강사가 거절하면 REJECTED·사유가 남고, 활성 신청이 0 이면 window bind 가 풀려 다시 AVAILABLE")
    void rejectUnbinds() throws Exception {
        Account ins = account("ins9@pd.com", "강사9");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        Enrollment e = submitOk(account("f@pd.com", "학생F"), course, w, (String) s[3], ticketRefOf((Venue) s[2]));

        mockMvc.perform(post("/instructor/enrollments/{id}/reject", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"일정이 안 맞아요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(windowRepo.findById(w.getId()).orElseThrow().getVenueRefId()).isNull();
        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(jsonPath("$._embedded.windows[0].status").value("AVAILABLE"));
    }

    /* ─── C* 취소 ──────────────────────────────────────────── */

    @Test
    @DisplayName("C1 학생이 대기 중 취소하면 CANCELLED 가 되고 window bind 가 풀린다")
    void studentCancelUnbinds() throws Exception {
        Account ins = account("ins10@pd.com", "강사10");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        AvailabilityWindow w = (AvailabilityWindow) s[1];
        Account stu = account("g@pd.com", "학생G");
        Enrollment e = submitOk(stu, course, w, (String) s[3], ticketRefOf((Venue) s[2]));

        mockMvc.perform(post("/enrollments/{id}/cancel", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(windowRepo.findById(w.getId()).orElseThrow().getVenueRefId()).isNull();
    }

    /* ─── G*·R* 게이트·권한 ────────────────────────────────── */

    @Test
    @DisplayName("G0 토큰 없이 옵션을 부르면 401")
    void optionsRequiresAuth() throws Exception {
        mockMvc.perform(get("/enrollments/options").param("courseId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R1 강사신청이 없는 사용자가 강사 신청목록을 보려 하면 400(게이트)")
    void instructorListGated() throws Exception {
        Account notInstructor = account("plain@pd.com", "보통사람");
        mockMvc.perform(get("/instructor/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(notInstructor)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R2 남의 신청을 취소하려 하면 400(존재 숨김)")
    void cannotCancelOthers() throws Exception {
        Account ins = account("ins11@pd.com", "강사11");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Enrollment e = submitOk(account("owner@pd.com", "주인"), (Course) s[0],
                (AvailabilityWindow) s[1], (String) s[3], ticketRefOf((Venue) s[2]));

        mockMvc.perform(post("/enrollments/{id}/cancel", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account("other@pd.com", "남"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R3 다른 강사의 코스 신청을 수락하려 하면 400")
    void cannotAcceptOthersCourseEnrollment() throws Exception {
        Account ins = account("ins12@pd.com", "강사12");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Enrollment e = submitOk(account("h@pd.com", "학생H"), (Course) s[0],
                (AvailabilityWindow) s[1], (String) s[3], ticketRefOf((Venue) s[2]));

        Account otherIns = account("ins13@pd.com", "강사13");
        enterInstructorTrack(otherIns);
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(otherIns)))
                .andExpect(status().isBadRequest());
    }

    /* ─── helper ─── */

    private Enrollment submitOk(Account student, Course course, AvailabilityWindow w,
                                String venueRef, String ticketRef) throws Exception {
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), w.getId(), venueRef, ticketRef, List.of()))))
                .andExpect(status().isCreated());
        return enrollmentRepo.findByStudentIdOrderByIdDesc(student.getId()).get(0);
    }
}
