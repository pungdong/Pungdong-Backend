package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.availability.AvailabilityCoverage;
import com.diving.pungdong.availability.AvailabilityCoverageJpaRepo;
import com.diving.pungdong.availability.AvailabilitySessionJpaRepo;
import com.diving.pungdong.course.*;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundEquipment;
import com.diving.pungdong.enrollment.EnrollmentRoundJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.venue.*;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 수강관리 hub use-case — {@code GET /instructor/enrollments/hub}. 거래 단위(수강생×강의) 카드 + 강사 시점
 * 상태/플래그 파생. {@code @DisplayName} = 사양. 실 H2 + 시큐리티 + 실 서비스. ⚠️ Authorization raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructorEnrollmentHubUseCaseTest {

    private static final LocalDate D1 = LocalDate.now().plusWeeks(1);
    private static final LocalDate D2 = LocalDate.now().plusWeeks(2);
    private static final LocalTime START = LocalTime.of(14, 0);
    private static final LocalTime END = LocalTime.of(17, 0);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired com.diving.pungdong.identityverification.IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired AvailabilityCoverageJpaRepo coverageRepo;
    @Autowired AvailabilitySessionJpaRepo sessionRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;
    @Autowired EnrollmentRoundJpaRepo roundRepo;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @AfterEach
    void clean() {
        enrollmentRepo.deleteAll();
        sessionRepo.deleteAll();
        coverageRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        identityVerificationRepo.deleteAll(); // account FK — 계정 삭제 전
        accountRepo.deleteAll();
    }

    private Account account(String email, String nick, Role role) {
        Account a = accountRepo.save(Account.builder().email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
        identityVerificationRepo.save(com.diving.pungdong.identityverification.IdentityVerification.builder()
                .account(a).status(com.diving.pungdong.identityverification.IdentityVerificationStatus.VERIFIED)
                .verifiedAt(OffsetDateTime.now(ZoneOffset.UTC)).build()); // 수강신청 게이트 통과용 본인인증
        return a;
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private Account instructor(String email, String nick) {
        Account ins = account(email, nick, Role.INSTRUCTOR);
        ins.setDefaultCapacity(4);
        accountRepo.save(ins);
        applicationRepo.save(InstructorApplication.builder().account(ins).disciplineCode("FREEDIVING")
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return ins;
    }

    private Venue venue(Account owner) {
        VenueDaypart weekday = VenueDaypart.builder().kind(DaypartKind.WEEKDAY).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekday.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueDaypart weekend = VenueDaypart.builder().kind(DaypartKind.WEEKEND).sold(true).fee(15000).timeMode(TimeMode.FIXED).build();
        weekend.addTimeBlock(VenueTimeBlock.builder().startTime(START).endTime(END).sortOrder(0).build());
        VenueTicket ticket = VenueTicket.builder().name("일반권").sortOrder(0)
                .disciplineCodes(new java.util.LinkedHashSet<>(Set.of("FREEDIVING"))).build();
        ticket.addDaypart(weekday); ticket.addDaypart(weekend);
        Venue venue = Venue.builder().owner(owner).name("잠실 잠수풀장").type(VenueType.SWIMMING_POOL)
                .address("서울 송파구").lockedDisciplineCode("FREEDIVING").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        venue.addTicket(ticket);
        return venueRepo.save(venue);
    }

    private Course course(Account ins, String venueRef, String ticketRef) {
        Course course = Course.builder().instructor(ins).title("AIDA2 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .totalRounds(2).price(300000).status(CourseStatus.OPEN).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        course.addRound(courseRound(1, venueRef, ticketRef));
        course.addRound(courseRound(2, venueRef, ticketRef));
        return courseRepo.save(course);
    }

    private CourseRound courseRound(int idx, String venueRef, String ticketRef) {
        CourseRound round = CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(idx).build();
        RoundVenue rv = RoundVenue.builder().venueRefId(venueRef).sortOrder(0).build();
        rv.addTicket(RoundVenueTicket.builder().ticketRef(ticketRef).daypart(DaypartKind.WEEKDAY).sortOrder(0).build());
        round.addVenue(rv);
        return round;
    }

    private void openCoverage(Account ins, LocalDate date) {
        coverageRepo.save(AvailabilityCoverage.builder().instructor(ins).date(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void applyRound1(Account stu, Course course, String ref, String ticket, LocalDate date) throws Exception {
        mockMvc.perform(post("/enrollments").header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", course.getId(), "date", date.toString(),
                                "venueRefId", ref, "ticketRef", ticket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("I1 신규 1회차 신청은 강사 hub 에서 ACTION_NEEDED · NEW_REQUEST · 회차 WAITING 으로 뜬다")
    void newRequestSurfaces() throws Exception {
        Account ins = instructor("ins-i1@pd.com", "강사I1");
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = course(ins, ref, ticket);
        openCoverage(ins, D1);
        Account stu = account("stu-i1@pd.com", "지원", Role.STUDENT);
        applyRound1(stu, course, ref, ticket, D1);

        mockMvc.perform(get("/instructor/enrollments/hub").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollments[0].status").value("ACTION_NEEDED"))
                .andExpect(jsonPath("$.enrollments[0].flag").value("NEW_REQUEST"))
                .andExpect(jsonPath("$.enrollments[0].student.name").value("지원"))
                .andExpect(jsonPath("$.enrollments[0].student.isNew").value(true))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].status").value("WAITING"))
                .andExpect(jsonPath("$.filters[1].id").value("action"))
                .andExpect(jsonPath("$.filters[1].count").value(1));
    }

    @Test
    @DisplayName("I2 학생이 직접 일정수정하면 강사 hub 에서 CHANGE_REQUEST · 회차 CHANGING · 직전 슬롯(previousSlot) 노출")
    void changeRequestSurfacesWithPreviousSlot() throws Exception {
        Account ins = instructor("ins-i2@pd.com", "강사I2");
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = course(ins, ref, ticket);
        openCoverage(ins, D1); openCoverage(ins, D2);
        Account stu = account("stu-i2@pd.com", "수민", Role.STUDENT);
        applyRound1(stu, course, ref, ticket, D1);
        EnrollmentRound r1 = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);

        // 학생 직접 일정수정 D1 → D2
        mockMvc.perform(post("/enrollments/rounds/{id}/reschedule", r1.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(stu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("date", D2.toString(), "venueRefId", ref, "ticketRef", ticket,
                                "blockStart", START.toString(), "blockEnd", END.toString()))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/instructor/enrollments/hub?filter=action").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollments[0].flag").value("CHANGE_REQUEST"))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].status").value("CHANGING"))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].date").value(D2.toString()))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].previousSlot.date").value(D1.toString()));
    }

    @Test
    @DisplayName("I3 회차 카드에 학생이 신청한 대여 장비 내역(gearItems: name·sizeLabel)이 그대로 echo 된다")
    void roundCardEchoesGearItems() throws Exception {
        Account ins = instructor("ins-i3@pd.com", "강사I3");
        Venue v = venue(ins);
        String ref = VenueScope.token(VenueScope.CUSTOM, String.valueOf(v.getId()));
        String ticket = v.getTickets().get(0).getRef();
        Course course = course(ins, ref, ticket);
        openCoverage(ins, D1);
        Account stu = account("stu-i3@pd.com", "장비러", Role.STUDENT);
        applyRound1(stu, course, ref, ticket, D1);

        // 신청된 회차에 대여 장비 2건 박제(핀 270, 슈트 L) — 신청 시점 스냅샷을 모사. LAZY 컬렉션 변경이라 트랜잭션 안에서.
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s -> {
            EnrollmentRound r = roundRepo.findByEnrollment_Student_IdOrderByIdDesc(stu.getId()).get(0);
            r.addEquipment(EnrollmentRoundEquipment.builder().itemRef("1").name("핀").priceSnapshot(5000).size("270").build());
            r.addEquipment(EnrollmentRoundEquipment.builder().itemRef("2").name("슈트").priceSnapshot(8000).size("L").build());
            roundRepo.save(r);
        });

        mockMvc.perform(get("/instructor/enrollments/hub").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearCount").value(2))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearItems", hasSize(2)))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearItems[0].name").value("핀"))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearItems[0].sizeLabel").value("270"))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearItems[1].name").value("슈트"))
                .andExpect(jsonPath("$.enrollments[0].rounds[0].gearItems[1].sizeLabel").value("L"));
    }
}
