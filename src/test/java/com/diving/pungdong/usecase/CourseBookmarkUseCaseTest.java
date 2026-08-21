package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CourseBookmarkJpaRepo;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.support.InstructorApprovalFixture;
import com.diving.pungdong.venue.Venue;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 강의 저장(북마크) use-case = 실행 가능한 사양. 강의 상세·둘러보기의 "저장" 버튼이 부르는 계약.
 * 실 H2 + 실 시큐리티 필터체인, {@code @MockBean} 없음. {@code @DisplayName} 을 위→아래로 읽으면
 * 저장 기능의 규칙이 된다.
 *
 * <p>그룹: <b>S*</b> 저장·해제 기본, <b>K*</b> 멱등(연타·재시도), <b>A*</b> 읽기 응답의 개인화 필드
 * (상세·카드, 비로그인 false), <b>F*</b> 저장한 강의 목록({@code ?bookmarkedByMe=true}),
 * <b>G*</b> 게이트(비공개 강의·인증).
 *
 * <p>이 피처에서 가장 틀리기 쉬운 두 곳: (1) <b>멱등</b> — 토글로 만들면 연타 결과가 순서에 달리고,
 * 삽입을 같은 트랜잭션에서 catch 하면 뒤이은 카운트가 500 이 된다. (2) <b>비로그인의 개인화 필드</b> —
 * 공개 읽기라 401 이 아니라 조용히 {@code false} 여야 한다(그 계약을 A3·F3 이 못 박는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseBookmarkUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired VenueJpaRepo venueRepo;
    @Autowired CourseJpaRepo courseRepo;
    @Autowired CourseBookmarkJpaRepo bookmarkRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;

    /** 삭제 순서는 FK 방향의 역순 — 저장(자식)을 먼저 걷어내지 않으면 강의 삭제가 제약 위반으로 터진다. */
    @AfterEach
    void cleanUp() {
        bookmarkRepo.deleteAll();
        courseRepo.deleteAll();
        venueRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
    }

    /* ════════════════ seed 헬퍼 ════════════════ */

    private Account account(String email) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(email.split("@")[0])
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String customRef(Account owner, String name) {
        Venue v = venueRepo.save(Venue.builder()
                .owner(owner).name(name).type(VenueType.SWIMMING_POOL)
                .address("서울특별시 송파구 올림픽로 25").lockedDisciplineCode("FREEDIVING")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        return "CUSTOM:" + v.getId();
    }

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    /** 강의 작성 → (선택) OPEN 전이. 저장 대상은 공개 표면에 보이는 강의뿐이라 OPEN 이 기본 단위다. */
    private long course(Account instructor, String title, String disciplineCode, boolean open) throws Exception {
        InstructorApprovalFixture.approve(applicationRepo, instructor, disciplineCode);
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("kind", "TRIAL");
        body.put("disciplineCode", disciplineCode);
        body.put("price", 90000);
        body.put("totalRounds", 1);
        body.put("rounds", List.of(Map.of("description", "1회차",
                "venues", List.of(Map.of("venueRefId", customRef(instructor, "잠실 잠수풀"),
                        "tickets", List.of(Map.of("ticketRef", "ticket-1", "daypart", "WEEKDAY")))))));
        String created = mockMvc.perform(post("/courses")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        if (open) {
            mockMvc.perform(patch("/courses/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                    .andExpect(status().isOk());
        }
        return id;
    }

    private long openCourse(Account instructor, String title) throws Exception {
        return course(instructor, title, "FREEDIVING", true);
    }

    /* ════════════════ S — 저장 · 해제 ════════════════ */

    @Test
    @DisplayName("S1: 강의를 저장하면 저장 수 1 + 내 상태 true 가 함께 온다 (낙관적 업데이트가 이 값으로 수렴한다)")
    void s1_bookmark_returnsCountAndActive() throws Exception {
        Account instructor = account("s1-i@pungdong.com");
        Account student = account("s1-s@pungdong.com");
        long courseId = openCourse(instructor, "저장할 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("S2: 저장을 해제하면 저장 수 0 + 내 상태 false 로 돌아온다")
    void s2_unbookmark_returnsZeroAndInactive() throws Exception {
        Account instructor = account("s2-i@pungdong.com");
        Account student = account("s2-s@pungdong.com");
        long courseId = openCourse(instructor, "해제할 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("S3: 저장 수는 사람 수다 — 두 사람이 같은 강의를 저장하면 2, 한 사람이 해제하면 1")
    void s3_countIsPerAccount() throws Exception {
        Account instructor = account("s3-i@pungdong.com");
        Account a = account("s3-a@pungdong.com");
        Account b = account("s3-b@pungdong.com");
        long courseId = openCourse(instructor, "둘이 저장한 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(a)))
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(b)))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(delete("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(a)))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.active").value(false));
    }

    /* ════════════════ K — 멱등 (연타 · 재시도) ════════════════ */

    @Test
    @DisplayName("K1: 저장을 두 번 눌러도 1개다 — 토글이 아니라 '저장된 상태로 만들어라'라서 꺼지지 않는다")
    void k1_bookmark_isIdempotent() throws Exception {
        Account instructor = account("k1-i@pungdong.com");
        Account student = account("k1-s@pungdong.com");
        long courseId = openCourse(instructor, "연타 대상 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("K2: 저장하지 않은 강의를 해제해도 에러가 아니라 0 + false 다 (해제도 멱등)")
    void k2_unbookmark_isIdempotent() throws Exception {
        Account instructor = account("k2-i@pungdong.com");
        Account student = account("k2-s@pungdong.com");
        long courseId = openCourse(instructor, "저장 안 한 강의");

        mockMvc.perform(delete("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.active").value(false));
    }

    /* ════════════════ A — 읽기 응답의 개인화 필드 ════════════════ */

    @Test
    @DisplayName("A1: 공개 상세에 내 저장 여부·저장 수가 인라인으로 온다 (버튼 상태를 그리려 따로 호출하지 않는다)")
    void a1_detail_inlinesBookmarkFields() throws Exception {
        Account instructor = account("a1-i@pungdong.com");
        Account student = account("a1-s@pungdong.com");
        long courseId = openCourse(instructor, "상세에서 저장한 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/" + courseId + "/detail")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarkedByMe").value(true))
                .andExpect(jsonPath("$.bookmarkCount").value(1));
    }

    @Test
    @DisplayName("A2: 남이 저장한 강의는 저장 수만 오르고 내 상태는 false 다 (남의 저장이 내 버튼을 켜지 않는다)")
    void a2_detail_otherPersonsBookmarkIsNotMine() throws Exception {
        Account instructor = account("a2-i@pungdong.com");
        Account other = account("a2-o@pungdong.com");
        Account me = account("a2-m@pungdong.com");
        long courseId = openCourse(instructor, "남이 저장한 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/" + courseId + "/detail")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarkCount").value(1))
                .andExpect(jsonPath("$.bookmarkedByMe").value(false));
    }

    @Test
    @DisplayName("A3: 비로그인 상세는 401 이 아니라 bookmarkedByMe=false 다 — 공개 읽기라 조용히 기본값이다")
    void a3_detail_anonymousGetsFalseNotError() throws Exception {
        Account instructor = account("a3-i@pungdong.com");
        Account student = account("a3-s@pungdong.com");
        long courseId = openCourse(instructor, "비로그인으로 볼 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/" + courseId + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarkCount").value(1))
                .andExpect(jsonPath("$.bookmarkedByMe").value(false));
    }

    @Test
    @DisplayName("A4: 둘러보기 카드에도 같은 두 필드가 실린다 (목록에서 저장 상태를 바로 그린다)")
    void a4_browseCard_inlinesBookmarkFields() throws Exception {
        Account instructor = account("a4-i@pungdong.com");
        Account student = account("a4-s@pungdong.com");
        long courseId = openCourse(instructor, "카드에서 저장한 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/browse?disciplineCode=FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].bookmarkedByMe").value(true))
                .andExpect(jsonPath("$._embedded.courses[0].bookmarkCount").value(1));

        // 같은 목록을 토큰 없이 읽으면 개인화만 빠진다 — 저장 수는 공개값이라 그대로다.
        mockMvc.perform(get("/courses/browse?disciplineCode=FREEDIVING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses[0].bookmarkedByMe").value(false))
                .andExpect(jsonPath("$._embedded.courses[0].bookmarkCount").value(1));
    }

    /* ════════════════ F — 저장한 강의 목록 ════════════════ */

    @Test
    @DisplayName("F1: ?bookmarkedByMe=true 는 내가 저장한 강의만 준다 (저장 안 한 강의는 빠진다)")
    void f1_savedListOnlyReturnsMine() throws Exception {
        Account instructor = account("f1-i@pungdong.com");
        Account student = account("f1-s@pungdong.com");
        long saved = openCourse(instructor, "저장한 강의");
        openCourse(instructor, "저장 안 한 강의");

        mockMvc.perform(post("/courses/" + saved + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/browse?bookmarkedByMe=true&disciplineCode=FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(1)))
                .andExpect(jsonPath("$._embedded.courses[0].title").value("저장한 강의"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("F2: 저장 목록은 종목을 안 보내도 된다 — 마이페이지에서 들어오므로 종목을 넘어 다 보인다")
    void f2_savedList_disciplineIsOptional() throws Exception {
        Account instructor = account("f2-i@pungdong.com");
        Account student = account("f2-s@pungdong.com");
        long free = course(instructor, "프리다이빙 저장", "FREEDIVING", true);
        long scuba = course(instructor, "스쿠버 저장", "SCUBA", true);

        mockMvc.perform(post("/courses/" + free + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/courses/" + scuba + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/browse?bookmarkedByMe=true")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.courses", hasSize(2)));

        // 종목 없는 '일반' 둘러보기는 그대로 400 이다 — 예외는 저장 목록에만 열린다.
        mockMvc.perform(get("/courses/browse"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("F3: 비로그인이 저장 목록을 요청하면 에러가 아니라 빈 페이지다 (저장한 게 없는 게 맞는 답이다)")
    void f3_savedList_anonymousGetsEmptyPage() throws Exception {
        Account instructor = account("f3-i@pungdong.com");
        openCourse(instructor, "누군가의 강의");

        mockMvc.perform(get("/courses/browse?bookmarkedByMe=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("F4: 저장은 남기고 목록에서만 빠진다 — 강의가 CLOSED 되면 저장 목록에서 사라지고, 다시 OPEN 되면 돌아온다")
    void f4_closedCourseDropsOutButBookmarkSurvives() throws Exception {
        Account instructor = account("f4-i@pungdong.com");
        Account student = account("f4-s@pungdong.com");
        long courseId = openCourse(instructor, "닫힐 강의");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/courses/" + courseId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "CLOSED"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/browse?bookmarkedByMe=true")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        // 저장 행은 살아 있다 — 다시 열리면 목록에 돌아온다("북마크가 날아간" 게 아니다).
        mockMvc.perform(patch("/courses/" + courseId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(instructor))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/courses/browse?bookmarkedByMe=true")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /* ════════════════ G — 게이트 (비공개 강의 · 인증) ════════════════ */

    @Test
    @DisplayName("G1: 아직 발행하지 않은(DRAFT) 강의는 저장할 수 없다 — 400 으로 존재를 숨긴다")
    void g1_draftCourseCannotBeBookmarked() throws Exception {
        Account instructor = account("g1-i@pungdong.com");
        Account student = account("g1-s@pungdong.com");
        long draft = course(instructor, "발행 안 한 강의", "FREEDIVING", false);

        mockMvc.perform(post("/courses/" + draft + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("G2: 없는 강의를 저장해도 같은 400 이다 (없음과 비공개가 응답으로 구분되지 않는다)")
    void g2_missingCourseSameResponse() throws Exception {
        Account student = account("g2-s@pungdong.com");

        mockMvc.perform(post("/courses/999999/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("G3: 토큰 없이 저장하면 401 이다 — 저장은 내가 누구인지 알아야 하는 행동이다")
    void g3_anonymousCannotBookmark() throws Exception {
        Account instructor = account("g3-i@pungdong.com");
        long courseId = openCourse(instructor, "토큰 없이 저장 시도");

        mockMvc.perform(post("/courses/" + courseId + "/bookmark"))
                .andExpect(status().isUnauthorized());
    }
}
