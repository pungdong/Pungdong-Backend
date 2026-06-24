package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.enrollment.dto.EnrollmentCreateRequest;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.global.sitesettings.SiteSettings;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 런칭 플래그(siteSettings) use-case — 실 H2 + Security 필터 + 실 서비스/JPA. {@link SiteSettingsProvider} 만
 * {@code @MockBean} 으로 갈아끼워 런칭 전/후·데모 노출 ON/OFF 를 직접 제어한다.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. P* 런칭 게이트(신청 차단) / S* 데모(seeded) 둘러보기
 * 필터 / D* 데모 상세 숨김. ⚠️ {@code Authorization} = raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LaunchFlagsUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired CourseJpaRepo courseRepo;

    @MockBean SiteSettingsProvider siteSettings;

    @AfterEach
    void cleanUp() {
        courseRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private Account account(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private Course openCourse(Account instructor, String title, boolean seeded) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title(title).kind(CourseKind.TRIAL)
                .disciplineCode("FREEDIVING").status(CourseStatus.OPEN)
                .price(10000).totalRounds(1).seeded(seeded)
                .createdAt(LocalDateTime.now()).build());
    }

    private String enrollmentBody(long courseId) throws Exception {
        return objectMapper.writeValueAsString(EnrollmentCreateRequest.builder()
                .courseId(courseId).date(LocalDate.now().plusWeeks(1))
                .venueRefId("CUSTOM:1").ticketRef("t")
                .blockStart(LocalTime.of(14, 0)).blockEnd(LocalTime.of(17, 0))
                .build());
    }

    @Test
    @DisplayName("P1: 런칭 전(launched=false)에는 수강신청이 전역 차단된다 (403, code -1016)")
    void preLaunchBlocksEnrollment() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(false, true));
        Account student = account("stud-p1@plop.cool", "p1");

        mockMvc.perform(post("/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, jwtTokenProvider.createAccessToken(
                                String.valueOf(student.getId()), student.getRoles()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollmentBody(999_999L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(-1016));
    }

    @Test
    @DisplayName("P2: 런칭 후(launched=true)에는 런칭 게이트를 통과한다 (없는 코스라 403 아닌 400)")
    void launchedPassesGate() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(true, true));
        Account student = account("stud-p2@plop.cool", "p2");

        mockMvc.perform(post("/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, jwtTokenProvider.createAccessToken(
                                String.valueOf(student.getId()), student.getRoles()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollmentBody(999_999L)))
                .andExpect(status().isBadRequest()); // 게이트 통과 → 코스 없음(ResourceNotFound=400)
    }

    @Test
    @DisplayName("S1: 데모 노출 OFF(showSeededCourses=false)면 둘러보기에서 seeded 코스가 빠진다")
    void browseHidesSeededWhenOff() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(true, false));
        Account inst = account("inst-s1@plop.cool", "s1inst");
        openCourse(inst, "실강사 강의", false);
        openCourse(inst, "샘플 강의", true);

        mockMvc.perform(get("/courses/browse").param("disciplineCode", "FREEDIVING").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.courses[0].seeded").value(false));
    }

    @Test
    @DisplayName("S2: 데모 노출 ON(showSeededCourses=true)면 seeded 코스도 함께 노출된다")
    void browseShowsSeededWhenOn() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(true, true));
        Account inst = account("inst-s2@plop.cool", "s2inst");
        openCourse(inst, "실강사 강의", false);
        openCourse(inst, "샘플 강의", true);

        mockMvc.perform(get("/courses/browse").param("disciplineCode", "FREEDIVING").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("D1: 데모 노출 OFF면 seeded 코스의 공개 상세도 숨겨진다 (400, 존재 숨김)")
    void detailHidesSeededWhenOff() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(true, false));
        Account inst = account("inst-d1@plop.cool", "d1inst");
        Course seeded = openCourse(inst, "샘플 강의", true);

        mockMvc.perform(get("/courses/{id}/detail", seeded.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("D2: 데모 노출 ON이면 seeded 코스 상세가 보이고 seeded=true 로 내려간다")
    void detailShowsSeededWhenOn() throws Exception {
        given(siteSettings.current()).willReturn(new SiteSettings(true, true));
        Account inst = account("inst-d2@plop.cool", "d2inst");
        Course seeded = openCourse(inst, "샘플 강의", true);

        mockMvc.perform(get("/courses/{id}/detail", seeded.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true));
    }
}
