package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
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
import java.util.HashSet;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강생 강의일정 hub use-case — {@code GET /enrollments/mine/schedule}. {@code @DisplayName} 위→아래 = 사양.
 *
 * <p>실 H2 + Security 필터 + 실 서비스. enrollment 를 <b>직접 저장</b>해(허브는 순수 read, 전체 자격흐름 불필요)
 * 강의 그룹핑·상태 파생·정렬·필터 카운트를 검증. ⚠️ Authorization raw JWT.
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
        enrollmentRepo.deleteAll();
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
                .createdAt(LocalDateTime.now()).build());
    }

    private void enroll(Account student, Course course, int round, EnrollmentStatus status, int total) {
        enrollmentRepo.save(Enrollment.builder()
                .student(student).course(course).roundIndex(round)
                .date(LocalDate.now().plusWeeks(1)).blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .venueRefId("CUSTOM:1").status(status)
                .tuitionSnapshot(total).entrySnapshot(0).equipmentSnapshot(0)
                .rejectionReason(status == EnrollmentStatus.REJECTED ? "그날은 일정이 있어요. 12/5 어떠세요?" : null)
                .createdAt(LocalDateTime.now()).build());
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    @Test
    @DisplayName("SH1 내 신청을 강의 단위로 그룹핑하고 진행상태를 파생한다(액션 우선 정렬 + 필터 카운트)")
    void groupsAndDerives() throws Exception {
        Account student = account("stu@pd.com", "학생");
        Account instructor = account("ins@pd.com", "김민지");
        Course a = course(instructor, "AIDA2 프리다이빙 과정");
        Course b = course(instructor, "PADI 프리다이버 과정");
        Course c = course(instructor, "SSI 베이직 프리다이버");

        // 강의 A: 1회차 결제대기(90,000) + 2회차 수락대기 → 강의=결제대기
        enroll(student, a, 1, EnrollmentStatus.PAYMENT_PENDING, 90000);
        enroll(student, a, 2, EnrollmentStatus.PENDING, 90000);
        // 강의 B: 확정 → 진행중
        enroll(student, b, 1, EnrollmentStatus.CONFIRMED, 350000);
        // 강의 C: 강사 거절 → 일정 변경
        enroll(student, c, 1, EnrollmentStatus.REJECTED, 50000);

        mockMvc.perform(get("/enrollments/mine/schedule").header(HttpHeaders.AUTHORIZATION, token(student)))
                .andExpect(status().isOk())
                // 정렬: PAYMENT_DUE → RESCHEDULING → WAITING → PROGRESS → CANCELLED
                .andExpect(jsonPath("$.courses.length()").value(3))
                .andExpect(jsonPath("$.courses[0].status").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$.courses[0].title").value("AIDA2 프리다이빙 과정"))
                .andExpect(jsonPath("$.courses[0].organizationCode").value("AIDA"))
                .andExpect(jsonPath("$.courses[0].instructorName").value("김민지"))
                .andExpect(jsonPath("$.courses[0].rounds.length()").value(2))
                // 회차는 roundIndex 순
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
    @DisplayName("SH2 인증 없이 호출하면 401 (matcher /enrollments/** authenticated)")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/enrollments/mine/schedule")).andExpect(status().isUnauthorized());
    }
}
