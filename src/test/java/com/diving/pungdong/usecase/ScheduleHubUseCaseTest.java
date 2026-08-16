package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseRound;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.enrollment.EnrollmentRoundEquipment;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강생 강의일정 hub use-case — {@code GET /enrollments/mine/schedule}. {@code @DisplayName} 위→아래 = 사양.
 *
 * <p>실 H2 + Security 필터 + 실 서비스. 수강(Enrollment) + 회차(EnrollmentRound)를 <b>직접 저장</b>해(허브는 순수
 * read) 강의 그룹핑·상태 파생·정렬·필터 카운트를 검증. 한 Enrollment = 한 강의 카드, 회차들이 round 행. ⚠️ raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleHubUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired EnrollmentJpaRepo enrollmentRepo;

    @AfterEach
    void clean() {
        enrollmentRepo.deleteAll(); // cascade → rounds
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private Course course(Account instructor, String title) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title(title)
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .levels(new HashSet<>(Set.of(CertLevel.LEVEL_2)))
                .totalRounds(1).price(350000).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private EnrollmentRound roundOf(int idx, EnrollmentStatus status) {
        return EnrollmentRound.builder()
                .roundIndex(idx).roundKind(RoundKind.REGULAR)
                .date(LocalDate.now().plusWeeks(1)).blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .venueRefId("CUSTOM:1").status(status).entrySnapshot(0).equipmentSnapshot(0)
                .rejectionReason(status == EnrollmentStatus.REJECTED ? "그날은 일정이 있어요. 12/5 어떠세요?" : null)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
    }

    private void enroll(Account student, Course course, int tuition, EnrollmentRound... rounds) {
        Enrollment e = Enrollment.builder()
                .student(student).course(course).tuitionSnapshot(tuition).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        for (EnrollmentRound r : rounds) {
            e.addRound(r);
        }
        enrollmentRepo.save(e);
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    @Test
    @DisplayName("SH1 내 수강을 강의 단위로 묶고 회차 진행상태를 파생한다(액션 우선 정렬 + 필터 카운트)")
    void groupsAndDerives() throws Exception {
        Account student = account("stu@pd.com", "학생");
        Account instructor = account("ins@pd.com", "김민지");
        Course a = course(instructor, "AIDA2 프리다이빙 과정");
        Course b = course(instructor, "PADI 프리다이버 과정");
        Course c = course(instructor, "SSI 베이직 프리다이버");

        // 강의 A: 1회차 미결제(수강료 90,000 → 1회차에 전액) + 2회차 결제완료·강사 확인 대기 → 강의=결제대기
        enroll(student, a, 90000, roundOf(1, EnrollmentStatus.PENDING), roundOf(2, EnrollmentStatus.ACCEPT_PENDING));
        // 강의 B: 확정 → 진행중
        enroll(student, b, 350000, roundOf(1, EnrollmentStatus.CONFIRMED));
        // 강의 C: 강사 거절 → 일정 변경
        enroll(student, c, 50000, roundOf(1, EnrollmentStatus.REJECTED));

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                // 정렬: PAYMENT_DUE → RESCHEDULING → WAITING → PROGRESS → CANCELLED
                .andExpect(jsonPath("$.courses.length()").value(3))
                .andExpect(jsonPath("$.courses[0].status").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$.courses[0].title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.courses[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.courses[0].instructorName").value("김민지"))
                .andExpect(jsonPath("$.courses[0].rounds.length()").value(2))
                // 회차는 roundIndex 순. 1회차 = 결제대기 + 수강료(90,000) 청구
                .andExpect(jsonPath("$.courses[0].rounds[0].roundIndex").value(1))
                .andExpect(jsonPath("$.courses[0].rounds[0].status").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$.courses[0].rounds[0].amount").value(90000))
                .andExpect(jsonPath("$.courses[0].rounds[1].status").value("WAITING"))
                .andExpect(jsonPath("$.courses[1].status").value("RESCHEDULING"))
                .andExpect(jsonPath("$.courses[1].rounds[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.courses[1].rounds[0].rejectionReason").value("그날은 일정이 있어요. 12/5 어떠세요?"))
                .andExpect(jsonPath("$.courses[2].status").value("PROGRESS"))
                // 필터: all + ORDER(PAYMENT_DUE,RESCHEDULING,WAITING,PROGRESS,CANCELLED) 고정 순서
                .andExpect(jsonPath("$.filters[0].id").value("all"))
                .andExpect(jsonPath("$.filters[0].count").value(3))
                .andExpect(jsonPath("$.filters[1].id").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$.filters[1].count").value(1))
                .andExpect(jsonPath("$.filters[2].id").value("RESCHEDULING"))
                .andExpect(jsonPath("$.filters[2].count").value(1))
                .andExpect(jsonPath("$.filters[3].id").value("WAITING"))
                .andExpect(jsonPath("$.filters[3].count").value(0))
                .andExpect(jsonPath("$.filters[4].id").value("PROGRESS"))
                .andExpect(jsonPath("$.filters[4].count").value(1));
    }

    @Test
    @DisplayName("SH6 정규를 다 끝낸 뒤 추가세션을 잡으면 카드는 PROGRESS 로 돌아가지만 certifiable 은 true 로 남는다")
    void certifiable_staysTrue_whileExtraRoundInProgress() throws Exception {
        Account student = account("sh6@pd.com", "학생6");
        Account instructor = account("sh6i@pd.com", "강사6");

        // 정규 1회차짜리 코스 — CourseRound 를 실제로 달아야 totalRegularRounds 가 1 이 된다.
        Course c = Course.builder()
                .instructor(instructor).title("AIDA1 자격 과정")
                .kind(CourseKind.CERTIFICATION).organizationCode("AIDA").disciplineCode("FREEDIVING")
                .levels(new HashSet<>(Set.of(CertLevel.LEVEL_1)))
                .totalRounds(1).price(300000).status(CourseStatus.OPEN)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        c.addRound(CourseRound.builder().roundKind(RoundKind.REGULAR).roundIndex(1).build());
        c = courseRepo.save(c);

        EnrollmentRound done = roundOf(1, EnrollmentStatus.CONFIRMED);
        done.setDoneAt(OffsetDateTime.now(ZoneOffset.UTC));      // 정규 1/1 이수 완료
        EnrollmentRound extra = roundOf(2, EnrollmentStatus.PENDING);
        extra.setRoundKind(RoundKind.EXTRA);
        extra.setRoundIndex(null);                                // EXTRA 는 정규 번호가 없다
        enroll(student, c, 300000, done, extra);

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                // 추가세션이 미결제라 카드 표시 상태는 되돌아간다 — 이건 의도된 동작이다.
                .andExpect(jsonPath("$.courses[0].status").value("PAYMENT_DUE"))
                // ★ 그래도 자격증은 이미 취득했다. 자격증 등록 피커는 status 가 아니라 이 값을 봐야 한다.
                .andExpect(jsonPath("$.courses[0].certifiable").value(true));
    }

    @Test
    @DisplayName("SH2 인증 없이 호출하면 401 (matcher /enrollments/** authenticated)")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/enrollments/mine/schedule")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SH3 회차에 내가 신청한 대여 장비 내역(gearItems: name·sizeLabel)이 echo 된다(강사 hub 와 대칭)")
    void roundEchoesGearItems() throws Exception {
        Account ins = account("ins-sh3@pd.com", "강사SH3");
        Account stu = account("stu-sh3@pd.com", "학생SH3");
        Course c = course(ins, "AIDA2 과정");
        EnrollmentRound r = roundOf(1, EnrollmentStatus.PENDING);
        r.addEquipment(EnrollmentRoundEquipment.builder().itemRef("1").name("핀").priceSnapshot(5000).size("270").build());
        r.addEquipment(EnrollmentRoundEquipment.builder().itemRef("2").name("슈트").priceSnapshot(8000).size("L").build());
        enroll(stu, c, 350000, r);

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds[0].gearItems", hasSize(2)))
                .andExpect(jsonPath("$.courses[0].rounds[0].gearItems[0].name").value("핀"))
                .andExpect(jsonPath("$.courses[0].rounds[0].gearItems[0].sizeLabel").value("270"))
                .andExpect(jsonPath("$.courses[0].rounds[0].gearItems[1].name").value("슈트"))
                .andExpect(jsonPath("$.courses[0].rounds[0].gearItems[1].sizeLabel").value("L"));
    }

    @Test
    @DisplayName("SH4 거절된 회차를 다시 신청하면 옛 거절 행은 hub 에서 빠진다(강의가 영구 RESCHEDULING 으로 굳지 않음)")
    void reappliedRoundSupersedesRejected() throws Exception {
        Account ins = account("ins-sh4@pd.com", "강사SH4");
        Account stu = account("stu-sh4@pd.com", "학생SH4");
        Course c = course(ins, "AIDA2 과정");
        // 1회차가 거절된 뒤 같은 회차를 다시 신청해 확정된 상태(같은 자리에 두 행 공존 — DB 유니크 제약 없음)
        enroll(stu, c, 350000, roundOf(1, EnrollmentStatus.REJECTED), roundOf(1, EnrollmentStatus.CONFIRMED));

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds.length()").value(1))
                .andExpect(jsonPath("$.courses[0].rounds[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.courses[0].status").value("PROGRESS")); // RESCHEDULING 아님
    }

    @Test
    @DisplayName("SH5 거절 후 아직 재신청 안 했으면 거절 행은 남아 '다시 잡아주세요'(RESCHEDULING)를 띄운다")
    void rejectedStaysUntilReapplied() throws Exception {
        Account ins = account("ins-sh5@pd.com", "강사SH5");
        Account stu = account("stu-sh5@pd.com", "학생SH5");
        Course c = course(ins, "AIDA2 과정");
        enroll(stu, c, 350000, roundOf(1, EnrollmentStatus.REJECTED));

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds.length()").value(1))
                .andExpect(jsonPath("$.courses[0].status").value("RESCHEDULING"));
    }

    @Test
    @DisplayName("SH-V1 위치 없는 회차만 가진 수강생도 강의일정이 200 이다 (venueRefId 전무 → Map.of().get(null) NPE 회귀)")
    void venuelessRoundsDoNotBreakHub() throws Exception {
        Account student = account("venueless@pd.com", "위치없는학생");
        Account instructor = account("ins-v@pd.com", "김민지");
        Course c = course(instructor, "AIDA1 자격 과정");

        // 위치 없는 점유(± 빠른조정 등)로 잡힌 회차 — venueRefId 가 null 이다.
        EnrollmentRound noVenue = EnrollmentRound.builder()
                .roundIndex(1).roundKind(RoundKind.REGULAR)
                .date(LocalDate.now().plusWeeks(1))
                .blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .venueRefId(null)
                .status(EnrollmentStatus.CONFIRMED).entrySnapshot(0).equipmentSnapshot(0)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        enroll(student, c, 300000, noVenue);

        // 고치기 전엔 여기서 500 이 났다 — 해석할 venueRefId 가 하나도 없어 resolveNames 가 Map.of() 를
        // 돌려주고, 불변 맵에 get(null) 을 하는 순간 NPE. 위치 있는 회차가 하나라도 섞이면 안 터져서
        // 기존 시나리오들이 전부 이 경로를 비껴갔다.
        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].rounds[0].venueName").doesNotExist());
    }
}
