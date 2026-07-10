package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.venue.ClosureType;
import com.diving.pungdong.venue.DaypartKind;
import com.diving.pungdong.venue.TimeMode;
import com.diving.pungdong.venue.VenueJpaRepo;
import com.diving.pungdong.venue.VenueType;
import com.diving.pungdong.venue.dto.VenueCreateRequest;
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

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 위치(Venue) 도메인 use-case 시나리오 — <b>강사 커스텀(CUSTOM) 위치</b>만. (공식 OFFICIAL 위치는
 * BE 가 아니라 Sanity authoring 이라 여기 없음.) 실제 H2 + Spring Security 필터 체인 + 실제 서비스/JPA.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 읽으면 사양. 그룹 — S* 성공 /
 * G* 생성 게이트 / V* 검증거절 / R* 권한·격리 / L* 카탈로그 필터.
 *
 * <p>핵심 정책: 커스텀 위치는 만든 강사 전용(비공개·종목 잠금). 생성은 승인이 아니라 그 종목 강사신청
 * 보유(리뷰 대기 SUBMITTED 포함)만 요구. ⚠️ 이 레포는 {@code Authorization} 헤더에 raw JWT(Bearer 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VenueUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired VenueJpaRepo venueRepo;

    @AfterEach
    void cleanUp() {
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

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    /** 그 종목 강사 트랙 진입(리뷰 대기 = SUBMITTED) — 커스텀 위치 생성 게이트 통과 조건. */
    private void enterInstructorTrack(Account account, String disciplineCode) {
        applicationRepo.save(InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode)
                .status(InstructorApplicationStatus.SUBMITTED)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ─── request builders ─────────────────────────────────── */

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    private static VenueCreateRequest.TimeBlock block(int sh, int sm, int eh, int em, int order) {
        return VenueCreateRequest.TimeBlock.builder()
                .startTime(t(sh, sm)).endTime(t(eh, em)).sortOrder(order).build();
    }

    private static VenueCreateRequest.Daypart weekdayFixed(int fee, List<VenueCreateRequest.TimeBlock> blocks) {
        return VenueCreateRequest.Daypart.builder()
                .kind(DaypartKind.WEEKDAY).sold(true).fee(fee).timeMode(TimeMode.FIXED).timeBlocks(blocks).build();
    }

    private static VenueCreateRequest.Daypart weekdayOpen(int fee, LocalTime open, LocalTime close, int hold) {
        return VenueCreateRequest.Daypart.builder()
                .kind(DaypartKind.WEEKDAY).sold(true).fee(fee).timeMode(TimeMode.OPEN)
                .openStart(open).openEnd(close).holdHours(hold).build();
    }

    private static VenueCreateRequest.Daypart weekendSame(int fee) {
        return VenueCreateRequest.Daypart.builder()
                .kind(DaypartKind.WEEKEND).sold(true).fee(fee).timeMode(TimeMode.SAME).build();
    }

    /** 커스텀 위치 — 종목 잠금 + 이용권 1개(평일 고정 + 주말 동일). dayparts 를 갈아끼울 수 있게 ticket 분리. */
    private VenueCreateRequest customVenue(String name, String lockedCode, List<String> ticketDisciplines,
                                           List<VenueCreateRequest.Daypart> dayparts) {
        return VenueCreateRequest.builder()
                .name(name).type(VenueType.OCEAN)
                .address("경북 울릉군 죽도").addressDetail("죽도 선착장 입구").maxDepth(30)
                .lockedDisciplineCode(lockedCode)
                .closures(List.of(
                        VenueCreateRequest.Closure.builder().type(ClosureType.WEEKLY)
                                .weekdays(List.of(DayOfWeek.MONDAY)).build(),
                        // 월간 atomic — "2째 주 수요일" 1건
                        VenueCreateRequest.Closure.builder().type(ClosureType.MONTHLY)
                                .nth(2).monthlyWeekday(DayOfWeek.WEDNESDAY).build()))
                .tickets(List.of(VenueCreateRequest.Ticket.builder()
                        .disciplineCodes(ticketDisciplines).dayparts(dayparts).build()))
                .build();
    }

    private VenueCreateRequest oceanFreediving() {
        return customVenue("울릉도 죽도 포인트", "FREEDIVING", List.of("FREEDIVING"),
                List.of(weekdayFixed(0, List.of(block(9, 0, 12, 0, 0), block(13, 0, 16, 0, 1))), weekendSame(0)));
    }

    /* ─── 시나리오 ─────────────────────────────────────────── */

    @Test
    @DisplayName("S1 강사가 리뷰 대기(SUBMITTED) 중에도 커스텀 위치를 만들 수 있고, owner=본인·종목 잠금된다")
    void s1_instructor_creates_custom() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("CUSTOM"))
                .andExpect(jsonPath("$.ownerId").value(inst.getId().intValue()))
                .andExpect(jsonPath("$.lockedDisciplineCode").value("FREEDIVING"))
                .andExpect(jsonPath("$.address").value("경북 울릉군 죽도"))
                .andExpect(jsonPath("$.addressDetail").value("죽도 선착장 입구"))
                .andExpect(jsonPath("$.maxDepth").value(30))
                .andExpect(jsonPath("$.tickets[0].disciplineCodes[0]").value("FREEDIVING"))
                .andExpect(jsonPath("$.closures.length()").value(2))
                .andExpect(jsonPath("$.closures[1].nth").value(2));

        org.assertj.core.api.Assertions.assertThat(venueRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("S2 상시 입장(OPEN) 평일 이용권은 응답에 이용시간(키반납 3h)이 파생되어 들어온다")
    void s2_open_mode_derives_duration() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");
        VenueCreateRequest req = customVenue("상시풀", "FREEDIVING", List.of("FREEDIVING"),
                List.of(weekdayOpen(15000, t(9, 0), t(22, 0), 3)));

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tickets[0].dayparts[0].timeMode").value("OPEN"))
                .andExpect(jsonPath("$.tickets[0].dayparts[0].holdHours").value(3));
    }

    @Test
    @DisplayName("G1 그 종목 강사신청이 없는 계정이 커스텀 위치를 만들려 하면 400 (강사 트랙 밖)")
    void g1_custom_without_application_rejected() throws Exception {
        Account stranger = account("nobody@pungdong.com", "구경꾼");

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V1 종목 잠금(lockedDisciplineCode) 없이 만들면 400")
    void v1_missing_locked_discipline_rejected() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");
        VenueCreateRequest req = oceanFreediving();
        req.setLockedDisciplineCode(null);

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 고정 시간대(FIXED)인데 시간블록이 0개면 400")
    void v2_fixed_without_blocks_rejected() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");
        VenueCreateRequest req = customVenue("블록없음", "FREEDIVING", List.of("FREEDIVING"),
                List.of(weekdayFixed(40000, List.of())));

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V3 잠긴 종목과 다른 종목을 이용권에 넣으면 400")
    void v3_discipline_mismatch_rejected() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");
        VenueCreateRequest req = customVenue("불일치", "FREEDIVING", List.of("SCUBA"),
                List.of(weekdayFixed(0, List.of(block(9, 0, 12, 0, 0)))));

        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R1 다른 강사의 커스텀 위치는 상세 조회되지 않는다 (400 — 존재를 숨김)")
    void r1_other_custom_not_visible() throws Exception {
        Account a = account("a@pungdong.com", "강사A");
        enterInstructorTrack(a, "FREEDIVING");
        Account b = account("b@pungdong.com", "강사B");

        String created = mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(a))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get("/venues/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R2 다른 강사의 커스텀 위치는 수정·삭제되지 않는다 (400)")
    void r2_other_custom_not_editable() throws Exception {
        Account a = account("a@pungdong.com", "강사A");
        enterInstructorTrack(a, "FREEDIVING");
        Account b = account("b@pungdong.com", "강사B");
        enterInstructorTrack(b, "FREEDIVING");

        String created = mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(a))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(delete("/venues/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("L1 GET /venues 는 내 커스텀만 — 남의 커스텀은 안 보이고, 종목 필터가 적용된다")
    void l1_only_my_custom_with_filter() throws Exception {
        Account me = account("me@pungdong.com", "나강사");
        enterInstructorTrack(me, "FREEDIVING");
        Account other = account("other@pungdong.com", "남강사");
        enterInstructorTrack(other, "FREEDIVING");

        postOk(me, oceanFreediving());
        VenueCreateRequest othersCustom = oceanFreediving();
        othersCustom.setName("남의 비밀 포인트");
        postOk(other, othersCustom);

        mockMvc.perform(get("/venues?disciplineCode=FREEDIVING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.venues[*].name", hasItem("울릉도 죽도 포인트")))
                .andExpect(jsonPath("$._embedded.venues[*].name", not(hasItem("남의 비밀 포인트"))));

        // 다른 종목으로 필터하면 내 프리다이빙 커스텀은 빠진다 → 빈 목록(_embedded 없음)
        mockMvc.perform(get("/venues?disciplineCode=SCUBA")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());
    }

    @Test
    @DisplayName("S3 위치를 수정해도 기존 이용권의 ticketRef 는 보존된다 (코스·수강신청 참조 유지)")
    void s3_ticketRef_preserved_across_update() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");

        String created = mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();
        String ref = objectMapper.readTree(created).get("tickets").get(0).get("ticketRef").asText();
        org.assertj.core.api.Assertions.assertThat(ref).isNotBlank();

        // 같은 이용권을 ticketRef 와 함께 다시 보내며 입장료만 50000 으로 수정
        VenueCreateRequest update = customVenue("울릉도 죽도 포인트", "FREEDIVING", List.of("FREEDIVING"),
                List.of(weekdayFixed(50000, List.of(block(9, 0, 12, 0, 0))), weekendSame(0)));
        update.getTickets().get(0).setTicketRef(ref);

        mockMvc.perform(put("/venues/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets[0].ticketRef").value(ref))          // 보존
                .andExpect(jsonPath("$.tickets[0].dayparts[0].fee").value(50000));  // 수정 반영
    }

    @Test
    @DisplayName("S4 수정 중 새 이용권을 추가하면 기존 ref 는 보존되고 신규는 새 ref 를 받는다")
    void s4_new_ticket_gets_fresh_ref_others_preserved() throws Exception {
        Account inst = account("inst@pungdong.com", "강사");
        enterInstructorTrack(inst, "FREEDIVING");

        String created = mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oceanFreediving())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();
        String ref = objectMapper.readTree(created).get("tickets").get(0).get("ticketRef").asText();

        VenueCreateRequest.Ticket existing = VenueCreateRequest.Ticket.builder()
                .ticketRef(ref).sortOrder(0).disciplineCodes(List.of("FREEDIVING"))
                .dayparts(List.of(weekdayFixed(0, List.of(block(9, 0, 12, 0, 0))), weekendSame(0))).build();
        VenueCreateRequest.Ticket added = VenueCreateRequest.Ticket.builder()
                .sortOrder(1).disciplineCodes(List.of("FREEDIVING"))  // ticketRef 없음 = 신규
                .dayparts(List.of(weekdayFixed(30000, List.of(block(13, 0, 16, 0, 0))), weekendSame(0))).build();
        VenueCreateRequest update = oceanFreediving();
        update.setTickets(List.of(existing, added));

        String body = mockMvc.perform(put("/venues/" + id)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(inst))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(2))
                .andExpect(jsonPath("$.tickets[0].ticketRef").value(ref))  // 기존 보존(sortOrder 0)
                .andReturn().getResponse().getContentAsString();
        String newRef = objectMapper.readTree(body).get("tickets").get(1).get("ticketRef").asText();
        org.assertj.core.api.Assertions.assertThat(newRef).isNotBlank().isNotEqualTo(ref);  // 신규는 새 ref
    }

    private void postOk(Account actor, VenueCreateRequest req) throws Exception {
        mockMvc.perform(post("/venues")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated());
    }
}
