package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.AccountBranding;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. P* = 공개 목록, <b>S* = 추천 강사</b>
 * ({@code GET /instructors/suggested}). 승인(APPROVED) 신청을 가진 실가입 강사만 카드가 되고,
 * 미승인/순수 학생/탈퇴는 빠진다. 카드는 공개 필드(nickName·아바타·종목)만.
 *
 * <p>두 목록의 <b>모집단이 다르다</b>: 디렉토리(P*)는 승인된 강사 전부, 추천(S*)은 그중
 * <b>프로필을 발행한</b> 강사만. 미발행 강사를 추천하면 눌렀을 때 400 이 나는 카드가 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicInstructorUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;

    @AfterEach
    void cleanUp() {
        applicationRepo.deleteAll();
        brandingRepo.deleteAll();
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
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /** 브랜딩 프로필. {@code published=false} 면 공개 상세가 안 열리므로 추천 대상도 아니다. */
    private void branding(Account account, boolean published) {
        brandingRepo.save(AccountBranding.builder()
                .account(account).isPublished(published).build());
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

    @Test
    @DisplayName("P5 과대한 size(100000)를 보내도 한 페이지는 50명까지다 — 강사 명단 전수 스크래핑 차단")
    void p5_sizeIsClamped() throws Exception {
        Account ins = account("ins-p5@pd.com", "상한강사", Role.INSTRUCTOR);
        application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);

        mockMvc.perform(get("/instructors/public").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));

        mockMvc.perform(get("/instructors/public")) // size 미지정 → 기본 20
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @DisplayName("P6 클라이언트가 보낸 정렬은 버려진다 — 순서는 서버 고정(가입 최신순)이고 없는 필드를 보내도 500 이 아니다")
    void p6_clientSortIsDiscarded() throws Exception {
        Account first = account("ins-p6a@pd.com", "먼저가입", Role.INSTRUCTOR);
        application(first, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        Account later = account("ins-p6b@pd.com", "나중가입", Role.INSTRUCTOR);
        application(later, "FREEDIVING", InstructorApplicationStatus.APPROVED);

        // 닉네임 오름차순을 요구해도 순서는 가입 최신순(id desc) 그대로다
        mockMvc.perform(get("/instructors/public").param("sort", "nickName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("나중가입"))
                .andExpect(jsonPath("$._embedded.instructors[1].nickName").value("먼저가입"));

        // 엔티티에 없는 필드를 정렬로 밀어 넣어도 쿼리에 섞이지 않는다
        mockMvc.perform(get("/instructors/public").param("sort", "bogusColumn,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    /* ─── S* 추천 강사 ─── */

    @Test
    @DisplayName("S1 추천은 프로필을 발행한 승인 강사만 준다 — 미발행 강사는 목록에도 totalCount 에도 없다")
    void suggested_onlyPublishedProfiles() throws Exception {
        Account shown = account("ins-s1a@pd.com", "보이는강사", Role.INSTRUCTOR);
        application(shown, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        branding(shown, true);

        Account unpublished = account("ins-s1b@pd.com", "미발행강사", Role.INSTRUCTOR);
        application(unpublished, "SCUBA", InstructorApplicationStatus.APPROVED);
        branding(unpublished, false);

        mockMvc.perform(get("/instructors/suggested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.instructors", hasSize(1)))
                .andExpect(jsonPath("$.instructors[0].nickName").value("보이는강사"))
                .andExpect(jsonPath("$.instructors[0].disciplineCodes",
                        containsInAnyOrder("FREEDIVING")));
    }

    @Test
    @DisplayName("S2 승인되지 않은 신청자는 프로필을 발행했어도 추천되지 않는다")
    void suggested_excludesUnapproved() throws Exception {
        Account pending = account("ins-s2@pd.com", "심사중강사", Role.STUDENT);
        application(pending, "FREEDIVING", InstructorApplicationStatus.SUBMITTED);
        branding(pending, true);

        mockMvc.perform(get("/instructors/suggested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.instructors", hasSize(0)));
    }

    @Test
    @DisplayName("S3 limit 보다 강사가 적으면 있는 만큼만 온다 (빈 목록도 200 — 실패가 아니라 사실이다)")
    void suggested_returnsFewerThanLimit() throws Exception {
        Account only = account("ins-s3@pd.com", "하나뿐인강사", Role.INSTRUCTOR);
        application(only, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        branding(only, true);

        mockMvc.perform(get("/instructors/suggested").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.instructors", hasSize(1)));
    }

    @Test
    @DisplayName("S4 limit 만큼만 잘라 주되 totalCount 는 전체 수를 그대로 말한다 (홈 카드의 'N명')")
    void suggested_limitCutsListNotCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            Account ins = account("ins-s4-" + i + "@pd.com", "강사" + i, Role.INSTRUCTOR);
            application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);
            branding(ins, true);
        }

        mockMvc.perform(get("/instructors/suggested").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.instructors", hasSize(2)));
    }

    @Test
    @DisplayName("S5 탈퇴한 강사는 추천되지 않는다 (디렉토리와 같은 축)")
    void suggested_excludesDeletedAccounts() throws Exception {
        Account gone = account("ins-s5@pd.com", "탈퇴강사", Role.INSTRUCTOR);
        application(gone, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        branding(gone, true);
        gone.setIsDeleted(true);
        accountRepo.save(gone);

        mockMvc.perform(get("/instructors/suggested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("S6 한 강사가 여러 종목을 승인받아도 카드는 1장이고 종목이 함께 실린다")
    void suggested_multiDisciplineIsOneCard() throws Exception {
        Account multi = account("ins-s6@pd.com", "멀티강사", Role.INSTRUCTOR);
        application(multi, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        application(multi, "SCUBA", InstructorApplicationStatus.APPROVED);
        branding(multi, true);

        mockMvc.perform(get("/instructors/suggested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.instructors", hasSize(1)))
                .andExpect(jsonPath("$.instructors[0].disciplineCodes",
                        containsInAnyOrder("FREEDIVING", "SCUBA")));
    }

    @Test
    @DisplayName("S7 추천 카드의 닉네임으로 공개 프로필이 실제로 열린다 (갈 곳 없는 추천을 만들지 않는다)")
    void suggested_cardLinksResolve() throws Exception {
        Account ins = account("ins-s7@pd.com", "열리는강사", Role.INSTRUCTOR);
        application(ins, "FREEDIVING", InstructorApplicationStatus.APPROVED);
        branding(ins, true);

        mockMvc.perform(get("/instructors/suggested"))
                .andExpect(jsonPath("$.instructors[0].nickName").value("열리는강사"));

        mockMvc.perform(get("/instructors/열리는강사"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("열리는강사"));
    }
}
