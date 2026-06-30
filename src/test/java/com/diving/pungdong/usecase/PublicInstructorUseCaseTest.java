package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 강사 디렉토리 use-case — GET /instructors/public (비로그인). 실 H2 + 시큐리티 체인.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. P* = 공개 목록. 승인(APPROVED) 신청을 가진
 * 실가입 강사만 카드가 되고, 미승인/순수 학생/탈퇴는 빠진다. 카드는 공개 필드(nickName·아바타·종목)만.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicInstructorUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;

    @AfterEach
    void cleanUp() {
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private void withPhoto(Account account, String url) {
        ProfilePhoto photo = profilePhotoRepo.save(ProfilePhoto.builder().imageUrl(url).build());
        account.setProfilePhoto(photo);
        accountRepo.save(account);
    }

    private void application(Account account, String disciplineCode, InstructorApplicationStatus status) {
        applicationRepo.save(InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode).status(status)
                .submittedAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
    }

    /* ─── P* 공개 디렉토리 ─── */

    @Test
    @DisplayName("P1 승인된 강사는 카드로 노출된다(nickName·아바타·종목, totalElements=강사 수) — 비로그인")
    void approvedInstructorAppears() throws Exception {
        Account ins = account("ins-p1@pd.com", "프리다이버", Role.INSTRUCTOR);
        withPhoto(ins, "https://cdn/p1.png");
        application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);

        mockMvc.perform(get("/instructors/public")) // Authorization 헤더 없음 — 공개
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("프리다이버"))
                .andExpect(jsonPath("$._embedded.instructors[0].avatarUrl").value("https://cdn/p1.png"))
                .andExpect(jsonPath("$._embedded.instructors[0].disciplineCodes",
                        containsInAnyOrder("FREEDIVING")));
    }

    @Test
    @DisplayName("P2 미승인(SUBMITTED) 신청자와 순수 학생은 디렉토리에 안 뜬다(승인 강사만)")
    void onlyApprovedListed() throws Exception {
        application(account("pending@pd.com", "검수중", Role.STUDENT), "FREEDIVING",
                InstructorApplicationStatus.SUBMITTED);
        account("student@pd.com", "그냥학생", Role.STUDENT); // 신청 없음

        mockMvc.perform(get("/instructors/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("P3 한 강사가 여러 종목 승인 → 카드 1장에 종목 코드가 합쳐진다")
    void multiDisciplineMergedIntoOneCard() throws Exception {
        Account ins = account("ins-p3@pd.com", "멀티강사", Role.INSTRUCTOR);
        application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        application(ins, "SCUBA", InstructorApplicationStatus.APPROVED);

        mockMvc.perform(get("/instructors/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1)) // distinct 계정 1
                .andExpect(jsonPath("$._embedded.instructors[0].disciplineCodes", hasSize(2)))
                .andExpect(jsonPath("$._embedded.instructors[0].disciplineCodes",
                        containsInAnyOrder("FREEDIVING", "SCUBA")));
    }

    @Test
    @DisplayName("P4 탈퇴(isDeleted)한 강사는 디렉토리에서 제외된다")
    void deletedInstructorExcluded() throws Exception {
        Account ins = account("ins-p4@pd.com", "탈퇴강사", Role.INSTRUCTOR);
        application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        ins.setIsDeleted(true);
        accountRepo.save(ins);

        mockMvc.perform(get("/instructors/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }
}
