package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.branding.AccountBranding;
import com.diving.pungdong.branding.AccountBrandingJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.instructorapplication.ApplicationCertificate;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강사 둘러보기(GET /instructors/browse) use-case = 실행 가능한 사양. 홈 "풍덩 공식 강사" 더보기에서
 * 들어오는 무한 스크롤 목록. 실 H2 + 실 시큐리티 체인, 비로그인 호출.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위→아래로 읽으면 이 목록의 규칙이 된다.
 * S* 성공(카드·필터·정렬·페이지) · O* 모수에서 빠지는 것 · V* 검증 · P* 페이지 상한.
 *
 * <p><b>이 화면의 핵심 규칙</b>: 카드는 <b>누르면 반드시 열려야</b> 한다. 그래서 모수가
 * "승인(그 종목) ∧ 브랜딩 발행 ∧ 미탈퇴" 다 — 기존 {@code /instructors/public} 은 발행을 보지 않아
 * 눌러도 400 인 카드가 섞인다. O1~O3 이 그 셋을 각각 잠근다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructorBrowseUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired AccountBrandingJpaRepo brandingRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired CourseJpaRepo courseRepo;

    @AfterEach
    void cleanUp() {
        courseRepo.deleteAll();
        applicationRepo.deleteAll();
        brandingRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ════════════════ seed 헬퍼 ════════════════ */

    private Account account(String nickName) {
        return accountRepo.save(Account.builder()
                .email(nickName + "@pd.com").password("x").nickName(nickName)
                .roles(new HashSet<>(Set.of(Role.INSTRUCTOR))).build());
    }

    private void withPhoto(Account account, String url) {
        account.setProfilePhoto(profilePhotoRepo.save(ProfilePhoto.builder().imageUrl(url).build()));
        accountRepo.save(account);
    }

    private void branding(Account account, boolean published, String tagline, String locationLabel) {
        brandingRepo.save(AccountBranding.builder()
                .account(account).isPublished(published).tagline(tagline).locationLabel(locationLabel).build());
    }

    /** 승인 신청 + (선택) 자격증 단체들. 단체는 자격증 단위라 한 신청에 여러 개가 붙는다. */
    private InstructorApplication application(Account account, String disciplineCode,
                                              InstructorApplicationStatus status, String... organizationCodes) {
        InstructorApplication application = InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode).status(status)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        for (String code : organizationCodes) {
            application.addCertificate(ApplicationCertificate.builder()
                    .organizationCode(code).fileKey("instructorCertificate/1/x.jpg").build());
        }
        return applicationRepo.save(application);
    }

    /** 공개중인 강의 1개. 카드의 "강의 N" 은 강의 둘러보기가 실제로 보여주는 것과 같은 조건으로 센다. */
    private Course course(Account instructor, String disciplineCode, CourseStatus status) {
        return courseRepo.save(Course.builder()
                .instructor(instructor).title("강의").kind(CourseKind.TRIAL)
                .disciplineCode(disciplineCode).status(status).price(90000).totalRounds(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    /** 카드 하나짜리 강사 — 승인 + 발행. 대부분의 시나리오가 여기서 출발한다. */
    private Account visibleInstructor(String nickName, String disciplineCode, String... organizationCodes) {
        Account account = account(nickName);
        branding(account, true, nickName + " 한 줄", "잠실 · 송파");
        application(account, disciplineCode, InstructorApplicationStatus.APPROVED, organizationCodes);
        return account;
    }

    private ResultActions browse(String query) throws Exception {
        return mockMvc.perform(get("/instructors/browse" + query)); // Authorization 헤더 없음 — 공개
    }

    /* ════════════════ S — 카드 · 필터 · 정렬 ════════════════ */

    @Test
    @DisplayName("S1 비로그인으로 부르면 승인·발행된 강사가 카드 필드(닉네임·아바타·한 줄·활동지역·종목·단체·강의수)와 함께 온다")
    void s1_card_fields() throws Exception {
        Account instructor = visibleInstructor("김민지", "FREEDIVING", "AIDA", "PADI");
        withPhoto(instructor, "https://cdn/minji.png");
        course(instructor, "FREEDIVING", CourseStatus.OPEN);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(1)))
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("김민지"))
                .andExpect(jsonPath("$._embedded.instructors[0].avatarUrl").value("https://cdn/minji.png"))
                .andExpect(jsonPath("$._embedded.instructors[0].tagline").value("김민지 한 줄"))
                .andExpect(jsonPath("$._embedded.instructors[0].locationLabel").value("잠실 · 송파"))
                .andExpect(jsonPath("$._embedded.instructors[0].disciplineCodes", contains("FREEDIVING")))
                .andExpect(jsonPath("$._embedded.instructors[0].organizationCodes",
                        containsInAnyOrder("AIDA", "PADI")))
                .andExpect(jsonPath("$._embedded.instructors[0].openCourseCount").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                // 순차 id 는 공개 표면에 싣지 않는다 — 프로필은 닉네임으로 연다
                .andExpect(jsonPath("$._embedded.instructors[0].id").doesNotExist());
    }

    @Test
    @DisplayName("S2 값이 비어 있는 필드는 키가 사라지지 않고 null 로 온다 (아바타·한 줄 소개·활동지역)")
    void s2_nullable_fields_are_null_not_absent() throws Exception {
        Account account = account("빈프로필");
        branding(account, true, null, null); // 발행은 했지만 내용은 비움
        application(account, "FREEDIVING", InstructorApplicationStatus.APPROVED);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                // 키가 사라지면 안 된다 — 값이 null 로 실려야 한다(hasJsonPath + null)
                .andExpect(jsonPath("$._embedded.instructors[0].avatarUrl").hasJsonPath())
                .andExpect(jsonPath("$._embedded.instructors[0].avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$._embedded.instructors[0].tagline").hasJsonPath())
                .andExpect(jsonPath("$._embedded.instructors[0].tagline").value(nullValue()))
                .andExpect(jsonPath("$._embedded.instructors[0].locationLabel").hasJsonPath())
                .andExpect(jsonPath("$._embedded.instructors[0].locationLabel").value(nullValue()))
                // 자격증이 없는 종목이면 단체는 빈 배열이다(null 아님)
                .andExpect(jsonPath("$._embedded.instructors[0].organizationCodes", hasSize(0)))
                .andExpect(jsonPath("$._embedded.instructors[0].openCourseCount").value(0));
    }

    @Test
    @DisplayName("S3 종목으로 좁힌다 — 스쿠버 강사는 프리다이빙 목록에 안 뜬다")
    void s3_filter_by_discipline() throws Exception {
        visibleInstructor("프리강사", "FREEDIVING");
        visibleInstructor("스쿠버강사", "SCUBA");

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(1)))
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("프리강사"));
    }

    @Test
    @DisplayName("S4 검색어로 강사 닉네임을 부분 일치로 찾는다")
    void s4_keyword() throws Exception {
        visibleInstructor("김민지", "FREEDIVING");
        visibleInstructor("박지원", "FREEDIVING");

        browse("?disciplineCode=FREEDIVING&keyword=민지")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(1)))
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("김민지"));
    }

    @Test
    @DisplayName("S5 단체 필터는 OR 합집합 — AIDA 또는 SSI 를 고르면 PADI 만 가진 강사는 빠진다")
    void s5_organization_filter_is_union() throws Exception {
        visibleInstructor("아이다", "FREEDIVING", "AIDA");
        visibleInstructor("에스에스아이", "FREEDIVING", "SSI");
        visibleInstructor("파디", "FREEDIVING", "PADI");

        browse("?disciplineCode=FREEDIVING&organizationCodes=AIDA&organizationCodes=SSI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(2)))
                .andExpect(jsonPath("$._embedded.instructors[*].nickName",
                        containsInAnyOrder("아이다", "에스에스아이")))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("S5b 단체는 요청한 종목의 자격증만 본다 — 스쿠버로 AIDA 를 가졌어도 프리다이빙 AIDA 필터엔 안 걸린다")
    void s5b_organization_is_scoped_to_discipline() throws Exception {
        Account account = account("종목혼합");
        branding(account, true, null, null);
        application(account, "FREEDIVING", InstructorApplicationStatus.APPROVED, "PADI");
        application(account, "SCUBA", InstructorApplicationStatus.APPROVED, "AIDA");

        browse("?disciplineCode=FREEDIVING&organizationCodes=AIDA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        browse("?disciplineCode=FREEDIVING&organizationCodes=PADI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(1)))
                // 카드의 단체도 요청 종목 것만 실린다 — 종목 코드는 반대로 승인 종목 전부다
                .andExpect(jsonPath("$._embedded.instructors[0].organizationCodes", contains("PADI")))
                .andExpect(jsonPath("$._embedded.instructors[0].disciplineCodes",
                        containsInAnyOrder("FREEDIVING", "SCUBA")));
    }

    @Test
    @DisplayName("S6 '강의 있음' 토글은 공개중인 강의가 0인 강사를 뺀다")
    void s6_has_open_course_toggle() throws Exception {
        Account teaching = visibleInstructor("강의있음", "FREEDIVING");
        course(teaching, "FREEDIVING", CourseStatus.OPEN);
        visibleInstructor("강의없음", "FREEDIVING");

        browse("?disciplineCode=FREEDIVING&hasOpenCourse=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors", hasSize(1)))
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("강의있음"));
        // 토글을 끄면 둘 다 — 목록과 totalElements 가 함께 움직인다
        browse("?disciplineCode=FREEDIVING")
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("S7 정렬=강의 많은순 이면 강의가 많은 강사가 먼저 온다 (기본은 최근 가입순)")
    void s7_sort_by_course_count() throws Exception {
        Account few = visibleInstructor("강의하나", "FREEDIVING");
        course(few, "FREEDIVING", CourseStatus.OPEN);
        Account many = visibleInstructor("강의셋", "FREEDIVING");
        course(many, "FREEDIVING", CourseStatus.OPEN);
        course(many, "FREEDIVING", CourseStatus.OPEN);
        course(many, "FREEDIVING", CourseStatus.OPEN);

        browse("?disciplineCode=FREEDIVING&sort=COURSE_COUNT_DESC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("강의셋"))
                .andExpect(jsonPath("$._embedded.instructors[0].openCourseCount").value(3))
                .andExpect(jsonPath("$._embedded.instructors[1].nickName").value("강의하나"));

        // 기본(LATEST) 은 최근 가입순 — 나중에 만든 '강의셋' 이 먼저지만 이유가 다르다
        browse("?disciplineCode=FREEDIVING")
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("강의셋"));
    }

    @Test
    @DisplayName("S8 페이지를 넘겨도 같은 강사가 두 번 나오지 않는다 (동수여도 순서가 결정적)")
    void s8_paging_is_deterministic() throws Exception {
        // 강의 수가 전부 0 이라 COURSE_COUNT_DESC 로는 전원 동점 — tie-break 가 없으면 여기서 샌다
        for (int i = 0; i < 4; i++) {
            visibleInstructor("강사" + i, "FREEDIVING");
        }

        String first = browse("?disciplineCode=FREEDIVING&sort=COURSE_COUNT_DESC&size=2&page=0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        String second = browse("?disciplineCode=FREEDIVING&sort=COURSE_COUNT_DESC&size=2&page=1")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> firstNames = com.jayway.jsonpath.JsonPath.read(first, "$._embedded.instructors[*].nickName");
        List<String> secondNames = com.jayway.jsonpath.JsonPath.read(second, "$._embedded.instructors[*].nickName");
        org.assertj.core.api.Assertions.assertThat(firstNames).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(secondNames).hasSize(2).doesNotContainAnyElementsOf(firstNames);
    }

    @Test
    @DisplayName("S9 카드의 닉네임으로 공개 프로필이 실제로 열린다 (갈 곳 없는 카드를 만들지 않는다)")
    void s9_card_links_resolve() throws Exception {
        visibleInstructor("열리는강사", "FREEDIVING");

        browse("?disciplineCode=FREEDIVING")
                .andExpect(jsonPath("$._embedded.instructors[0].nickName").value("열리는강사"));
        mockMvc.perform(get("/instructors/열리는강사"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("열리는강사"));
    }

    /* ════════════════ O — 모수에서 빠지는 것 ════════════════ */

    @Test
    @DisplayName("O1 프로필을 발행하지 않은 강사는 안 뜬다 — 뜨면 눌렀을 때 400 이 나는 카드가 된다")
    void o1_unpublished_excluded() throws Exception {
        Account account = account("미발행강사");
        branding(account, false, null, null);
        application(account, "FREEDIVING", InstructorApplicationStatus.APPROVED);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // 그 카드가 떴다면 갔을 곳 — 실제로 열리지 않는다는 사실을 함께 못박는다
        mockMvc.perform(get("/instructors/미발행강사"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("O2 심사중(SUBMITTED)·반려된 신청자는 프로필을 발행했어도 안 뜬다")
    void o2_unapproved_excluded() throws Exception {
        Account pending = account("심사중");
        branding(pending, true, null, null);
        application(pending, "FREEDIVING", InstructorApplicationStatus.SUBMITTED);

        Account rejected = account("반려됨");
        branding(rejected, true, null, null);
        application(rejected, "FREEDIVING", InstructorApplicationStatus.REJECTED);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("O3 탈퇴한 강사는 안 뜬다")
    void o3_deleted_excluded() throws Exception {
        Account gone = visibleInstructor("탈퇴강사", "FREEDIVING");
        gone.setIsDeleted(true);
        accountRepo.save(gone);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("O4 '강의 N' 은 강의 둘러보기가 보여주는 것만 센다 — DRAFT·CLOSED·차단·다른 종목은 빠진다")
    void o4_open_course_count_matches_browse() throws Exception {
        Account instructor = visibleInstructor("강사", "FREEDIVING");
        course(instructor, "FREEDIVING", CourseStatus.OPEN);      // 셈
        course(instructor, "FREEDIVING", CourseStatus.DRAFT);     // 안 셈
        course(instructor, "FREEDIVING", CourseStatus.CLOSED);    // 안 셈
        course(instructor, "SCUBA", CourseStatus.OPEN);           // 안 셈 — 다른 종목
        Course blocked = course(instructor, "FREEDIVING", CourseStatus.OPEN);
        blocked.setBlockedAt(OffsetDateTime.now(ZoneOffset.UTC)); // 안 셈 — 어드민 조치
        courseRepo.save(blocked);

        browse("?disciplineCode=FREEDIVING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.instructors[0].openCourseCount").value(1));
    }

    /* ════════════════ V — 검증 · 빈 결과 ════════════════ */

    @Test
    @DisplayName("V1 종목(disciplineCode) 없이 부르면 400 — 종목은 필수다")
    void v1_discipline_required() throws Exception {
        browse("").andExpect(status().isBadRequest());
        browse("?disciplineCode=").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 결과가 없으면 에러가 아니라 200 빈 페이지 — 없는 종목 코드도 마찬가지다")
    void v2_empty_is_ok() throws Exception {
        browse("?disciplineCode=MERMAID")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$._embedded").doesNotExist()); // 0건이면 _embedded 키 자체가 없다
    }

    /* ════════════════ P — 페이지 상한 ════════════════ */

    @Test
    @DisplayName("P1 과대한 size(100000)를 보내도 한 페이지는 50명까지다 — 강사 명단 전수 스크래핑 차단")
    void p1_size_is_clamped() throws Exception {
        visibleInstructor("강사", "FREEDIVING");

        browse("?disciplineCode=FREEDIVING&size=100000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
        browse("?disciplineCode=FREEDIVING")
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @DisplayName("P2 정렬 창구는 화이트리스트 enum 뿐 — Pageable 형식(sort=id,asc)은 400 이다")
    void p2_client_sort_is_not_a_backdoor() throws Exception {
        browse("?disciplineCode=FREEDIVING&sort=id,asc")
                .andExpect(status().isBadRequest());
    }
}
