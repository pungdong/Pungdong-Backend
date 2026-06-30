package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySession;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강신청(enrollment/booking) use-case — 실 H2 + Spring Security 필터 + 실 서비스/JPA.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. O* 옵션 교집합 / S* 신청 / J* 합류·자격 / F* 만석 /
 * A* 강사 수락·거절 / C* 취소 / G*·R* 게이트·권한.
 *
 * <p>핵심: 슬롯 = 강사 <b>coverage(예약가능시간) ∩ venue 운영블록</b>(부 전체가 coverage 에 ⊆). 첫 신청이 그
 * (위치,블록) session 을 생성, 같은 (위치,블록) 신청은 join. 정원 = 계정 기본값(여기선 강사 defaultCapacity 로
 * 결정적 셋업). 신청 PENDING → 강사 수락 시 PAYMENT_PENDING(결제 대기) → 결제 승인 시 CONFIRMED(결제는
 * PaymentUseCaseTest). 수락은 슬롯을 점유. 캘린더 sessions[] 에 반영. ⚠️ raw JWT.
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
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired VenueEquipmentExtensionJpaRepo equipmentRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalTime B_START = LocalTime.of(14, 0);
    private static final LocalTime B_END = LocalTime.of(17, 0);

    @AfterEach
    void cleanUp() {
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        equipmentRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ─── fixtures ─── */

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

    /** 잠실풀 — 일반권, 평일·주말 FIXED 블록 09–12·14–17(둘 다 fee 15,000 → 날짜 무관 결정적). */
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

    /** 그 강사의 D1 coverage [start,end] 직접 개방(정규화된 단일 구간). */
    private void openCoverage(Account instructor, LocalTime start, LocalTime end) {
        coverageRepo.save(AvailabilityCoverage.builder()
                .instructor(instructor).date(D1).startTime(start).endTime(end).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EnrollmentCreateRequest req(Long courseId, String venueRef, String ticketRef,
                                        LocalTime bStart, LocalTime bEnd, List<String> equip) {
        return EnrollmentCreateRequest.builder()
                .courseId(courseId).date(D1)
                .venueRefId(venueRef).ticketRef(ticketRef)
                .blockStart(bStart).blockEnd(bEnd).equipmentRefs(equip).build();
    }

    /**
     * 강사·venue·course 한 세트 + coverage 09–18 개방. 정원 cap 은 강사 defaultCapacity 로 박아 결정적(세션은
     * 첫 신청에 override 없이 생성되어 그 값을 따른다). 인덱스 [course, venue, venueRef].
     */
    private Object[] setup(Account instructor, int cap) {
        instructor.setDefaultCapacity(cap);
        accountRepo.save(instructor);
        Venue venue = saveVenue(instructor);
        String venueRef = venueRefOf(venue);
        Course course = saveCourse(instructor, venueRef, ticketRefOf(venue));
        openCoverage(instructor, LocalTime.of(9, 0), LocalTime.of(18, 0));
        return new Object[]{course, venue, venueRef};
    }

    /* ─── O* 옵션 교집합 ─── */

    @Test
    @DisplayName("O1 신청 옵션은 강사 coverage ∩ venue 운영블록 ∩ 코스 1회차 위치 교집합 슬롯을 준다")
    void optionsIntersection() throws Exception {
        Account ins = account("ins1@pd.com", "강사1");
        enterInstructorTrack(ins);
        Course course = (Course) setup(ins, 4)[0];
        Account stu = account("stu1@pd.com", "수강생1");

        mockMvc.perform(get("/enrollments/options")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .param("courseId", String.valueOf(course.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(2)) // 09–12·14–17 둘 다 09–18 coverage 에 ⊆
                .andExpect(jsonPath("$.slots[0].capacity").value(4))
                .andExpect(jsonPath("$.slots[0].remaining").value(4))
                .andExpect(jsonPath("$.slots[0].entryFee").value(15000))
                .andExpect(jsonPath("$.course.price").value(350000));
    }

    @Test
    @DisplayName("O2 venue 부가 coverage 에 통째로 안 들어가면(부분만 겹침) 슬롯에서 빠진다")
    void optionsExcludesBlocksOutsideCoverage() throws Exception {
        Account ins = account("ins2@pd.com", "강사2");
        enterInstructorTrack(ins);
        ins.setDefaultCapacity(4);
        accountRepo.save(ins);
        Venue venue = saveVenue(ins);
        Course course = saveCourse(ins, venueRefOf(venue), ticketRefOf(venue));
        openCoverage(ins, B_START, B_END); // 14–17 만 → 09–12 부는 빠짐
        Account stu = account("stu2@pd.com", "수강생2");

        mockMvc.perform(get("/enrollments/options")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .param("courseId", String.valueOf(course.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(1));
    }

    @Test
    @DisplayName("O3 강사 기존 일정과 시간이 겹치는 venue 부는 빠지지 않고 TIME_CONFLICT 로 표기된다(이중부킹은 비활성 노출)")
    void optionsFlagOverlappingBlock() throws Exception {
        Account ins = account("ins14@pd.com", "강사14");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4); // coverage 09–18, venue 부 09–12·14–17
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        // 09–12 부와 겹치는(정확히 같지 않은) 기존 일정 10–12 직접 생성
        sessionRepo.save(AvailabilitySession.builder().instructor(ins).date(D1)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(12, 0)).venueRefId(venueRef)
                .createdAt(LocalDateTime.now()).build());
        Account stu = account("stu14@pd.com", "수강생14");

        mockMvc.perform(get("/enrollments/options")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .param("courseId", String.valueOf(course.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(2)) // 둘 다 옴(필터 아님)
                // 겹치는 09–12 부는 TIME_CONFLICT 로 비활성 표기
                .andExpect(jsonPath("$.slots[?(@.blockStart == '09:00:00')].unavailableReason")
                        .value(hasItem("TIME_CONFLICT")))
                // 겹치지 않는 14–17 부는 선택 가능
                .andExpect(jsonPath("$.slots[?(@.blockStart == '14:00:00' && @.unavailableReason == 'TIME_CONFLICT')]")
                        .isEmpty());
    }

    /* ─── S* 신청 ─── */

    @Test
    @DisplayName("S1 학생이 신청하면 PENDING 이 생기고 캘린더 session 에 대기 1명·신청자가 반영된다")
    void submitPendingReflectsOnCalendar() throws Exception {
        Account ins = account("ins3@pd.com", "강사3");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        Account stu = account("stu3@pd.com", "수강생3");

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRefOf((Venue) s[1]), B_START, B_END, List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(365000));

        assertThat(enrollmentRepo.findAll()).hasSize(1);

        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].pendingCount").value(1))
                .andExpect(jsonPath("$.sessions[0].status").value("PENDING"))
                .andExpect(jsonPath("$.sessions[0].applicants[0].name").value("수강생3"))
                .andExpect(jsonPath("$.sessions[0].venueName").value("잠실 잠수풀장"))
                // sessionLabel 은 저장 라벨이 아니라 ticketRef 로 해석한 이용권 명칭(단일 출처)
                .andExpect(jsonPath("$.sessions[0].sessionLabel").value("일반권"));
    }

    @Test
    @DisplayName("S2 장비를 함께 신청하면 장비 가격이 스냅샷되고 총액에 합산된다")
    void submitWithEquipment() throws Exception {
        Account ins = account("ins4@pd.com", "강사4");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        VenueEquipmentItem fin = saveEquipment(ins, venueRef);
        Account stu = account("stu4@pd.com", "수강생4");

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRefOf((Venue) s[1]), B_START, B_END,
                        List.of(String.valueOf(fin.getId()))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentTotal").value(5000))
                .andExpect(jsonPath("$.total").value(370000))
                .andExpect(jsonPath("$.equipment[0].name").value("롱핀"));
    }

    /* ─── J* 합류·자격 ─── */

    @Test
    @DisplayName("J1 같은 venue·정확히 같은 블록이면 둘째 학생도 같은 session 에 합류(둘 다 PENDING)")
    void joinSameSlot() throws Exception {
        Account ins = account("ins5@pd.com", "강사5");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        String ticketRef = ticketRefOf((Venue) s[1]);

        submitOk(account("a@pd.com", "학생A"), course, venueRef, ticketRef);
        submitOk(account("b@pd.com", "학생B"), course, venueRef, ticketRef);

        assertThat(sessionRepo.findAll()).hasSize(1); // 하나의 session 으로 합류
        long sid = sessionRepo.findAll().get(0).getId();
        assertThat(roundRepo.findByAvailabilitySessionIdAndStatusIn(
                sid, List.of(EnrollmentStatus.PENDING))).hasSize(2);
    }

    @Test
    @DisplayName("J2 그 부가 강사 coverage 에 통째로 안 들어가면 신청 거절(서버 재검증)")
    void rejectBlockOutsideCoverage() throws Exception {
        Account ins = account("ins6@pd.com", "강사6");
        enterInstructorTrack(ins);
        ins.setDefaultCapacity(4);
        accountRepo.save(ins);
        Venue venue = saveVenue(ins);
        String venueRef = venueRefOf(venue);
        Course course = saveCourse(ins, venueRef, ticketRefOf(venue));
        openCoverage(ins, B_START, B_END); // 14–17 만 — 09–12 부는 coverage 밖

        EnrollmentCreateRequest other = req(course.getId(), venueRef, ticketRefOf(venue),
                LocalTime.of(9, 0), LocalTime.of(12, 0), List.of());
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account("b2@pd.com", "학생B2")))
                .contentType(MediaType.APPLICATION_JSON).content(json(other)))
                .andExpect(status().isBadRequest());
    }

    /* ─── F* 만석 ─── */

    @Test
    @DisplayName("F1 정원이 수락(결제대기)으로 다 차면(만석) 새 신청은 400")
    void fullRejectsNewApplication() throws Exception {
        Account ins = account("ins7@pd.com", "강사7");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 1); // 정원 1
        Course course = (Course) s[0];
        String venueRef = (String) s[2];
        String ticketRef = ticketRefOf((Venue) s[1]);

        EnrollmentRound first = submitOk(account("c@pd.com", "학생C"), course, venueRef, ticketRef);
        // 수락 = 결제 대기(슬롯 점유). 결제 전이라도 정원을 차지하므로 새 신청은 만석.
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", first.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(account("d@pd.com", "학생D")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRef, B_START, B_END, List.of()))))
                .andExpect(status().isBadRequest());
    }

    /* ─── A* 강사 수락·거절 ─── */

    @Test
    @DisplayName("A1 강사가 수락하면 PAYMENT_PENDING(결제 대기)이 되고 캘린더엔 점유 1명으로 반영된다")
    void acceptConfirms() throws Exception {
        Account ins = account("ins8@pd.com", "강사8");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        EnrollmentRound e = submitOk(account("e@pd.com", "학생E"), (Course) s[0], (String) s[2], ticketRefOf((Venue) s[1]));

        mockMvc.perform(post("/instructor/enrollments/{id}/accept", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins)))
                .andExpect(status().isOk());

        // 수락 = 결제 대기(아직 확정 아님). 결제 승인이 CONFIRMED 로 넘긴다(PaymentUseCaseTest).
        assertThat(roundRepo.findById(e.getId()).orElseThrow().getStatus())
                .isEqualTo(EnrollmentStatus.PAYMENT_PENDING);
        // 캘린더는 점유(결제대기+확정)를 confirmed 버킷으로 합산 — 슬롯은 차지된 것으로 표시(v1).
        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(jsonPath("$.sessions[0].confirmedCount").value(1))
                .andExpect(jsonPath("$.sessions[0].status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("A2 강사가 거절해 그 일정이 0명이 되면 일정 카드는 사라지되(캘린더 session 0개) 거절 이력은 보존된다")
    void rejectEmptiesAndDeletesSessionButKeepsHistory() throws Exception {
        Account ins = account("ins9@pd.com", "강사9");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        EnrollmentRound e = submitOk(account("f@pd.com", "학생F"), (Course) s[0], (String) s[2], ticketRefOf((Venue) s[1]));

        mockMvc.perform(post("/instructor/enrollments/{id}/reject", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"일정이 안 맞아요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // 빈 일정 카드는 캘린더에서 사라짐(coverage 는 유지)
        mockMvc.perform(get("/instructor/availability")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(ins))
                .param("from", D1.minusDays(1).toString()).param("to", D1.plusDays(1).toString()))
                .andExpect(jsonPath("$.sessions.length()").value(0))
                .andExpect(jsonPath("$.coverage.length()").value(1));
        assertThat(sessionRepo.findAll()).isEmpty();

        // 거절 이력은 보존 — enrollment 는 REJECTED·사유·스냅샷(날짜·위치·블록) 그대로, session FK 만 끊김
        EnrollmentRound hist = roundRepo.findById(e.getId()).orElseThrow();
        assertThat(hist.getStatus()).isEqualTo(EnrollmentStatus.REJECTED);
        assertThat(hist.getRejectionReason()).isEqualTo("일정이 안 맞아요");
        assertThat(hist.getDate()).isEqualTo(D1);
        assertThat(hist.getVenueRefId()).isEqualTo((String) s[2]);
        assertThat(hist.getAvailabilitySession()).isNull();
    }

    /* ─── C* 취소 ─── */

    @Test
    @DisplayName("C1 학생이 대기 중 취소해 일정이 0명이 되면 일정은 사라지되 취소 이력은 보존된다")
    void studentCancelDeletesSessionButKeepsHistory() throws Exception {
        Account ins = account("ins10@pd.com", "강사10");
        enterInstructorTrack(ins);
        Object[] s = setup(ins, 4);
        Account stu = account("g@pd.com", "학생G");
        EnrollmentRound e = submitOk(stu, (Course) s[0], (String) s[2], ticketRefOf((Venue) s[1]));

        mockMvc.perform(post("/enrollments/{id}/cancel", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(stu)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(sessionRepo.findAll()).isEmpty(); // 빈 일정 삭제
        // 취소 이력 보존 — enrollment 는 CANCELLED·스냅샷 그대로
        EnrollmentRound hist = roundRepo.findById(e.getId()).orElseThrow();
        assertThat(hist.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(hist.getDate()).isEqualTo(D1);
        assertThat(hist.getAvailabilitySession()).isNull();
    }

    /* ─── G*·R* 게이트·권한 ─── */

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
        EnrollmentRound e = submitOk(account("owner@pd.com", "주인"), (Course) s[0], (String) s[2], ticketRefOf((Venue) s[1]));

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
        EnrollmentRound e = submitOk(account("h@pd.com", "학생H"), (Course) s[0], (String) s[2], ticketRefOf((Venue) s[1]));

        Account otherIns = account("ins13@pd.com", "강사13");
        enterInstructorTrack(otherIns);
        mockMvc.perform(post("/instructor/enrollments/{id}/accept", e.getId())
                .header(HttpHeaders.AUTHORIZATION, tokenFor(otherIns)))
                .andExpect(status().isBadRequest());
    }

    /* ─── helper ─── */

    private EnrollmentRound submitOk(Account student, Course course, String venueRef, String ticketRef) throws Exception {
        mockMvc.perform(post("/enrollments")
                .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req(course.getId(), venueRef, ticketRef, B_START, B_END, List.of()))))
                .andExpect(status().isCreated());
        return roundRepo.findByEnrollment_Student_IdOrderByIdDesc(student.getId()).get(0);
    }
}
