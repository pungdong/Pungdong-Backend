package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.account.dto.signUp.SignUpInfo;
import com.diving.pungdong.global.security.JwtTokenProvider;
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

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 닉네임 정책 — 형식 가드 + 예약어(브랜드·운영자 사칭·라우트 충돌) 차단.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양.
 * S* 통과 / V* 형식 거부 / R* 예약어 거부 / F* 오탐 없음 / C* 중복확인 응답 / A* 어드민 예외.
 *
 * <p>닉네임은 표시명이자 <b>공개 URL 식별자</b>({@code /instructors/{nickName}})라 세 가지가 걸린다 —
 * 라우트 충돌(그 계정 프로필이 영영 안 열림)·사칭·우리가 쓸 이름의 선점. 세 축이 각각 어떤 입력에서
 * 어떻게 막히는지를 여기서 못 박는다.
 *
 * <p>가장 틀리기 쉬운 지점 둘: (1) 중복확인이 {@code exists:false} 라고 <b>쓸 수 있다는 뜻이 아니다</b>
 * (C*), (2) 차단 규칙이 넓어지면 멀쩡한 이름을 잡는다 — {@code badminton} 이 {@code admin} 에 걸리면
 * 안 된다(F*).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NickNamePolicyUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;

    @AfterEach
    void cleanUp() {
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ── fixture ─────────────────────────────────────────── */

    private String signUpJson(String nickName) throws Exception {
        return objectMapper.writeValueAsString(SignUpInfo.builder()
                .email("nick" + Math.abs(nickName.hashCode()) + "@example.com")
                .password("pw1234ab")
                .nickName(nickName)
                .build());
    }

    private int signUpStatus(String nickName) throws Exception {
        return mockMvc.perform(post("/sign/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpJson(nickName)))
                .andReturn().getResponse().getStatus();
    }

    private Account account(String email, String nickName, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nickName)
                .roles(new HashSet<>(Set.of(role))).isDeleted(false).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    private int patchNickNameStatus(Account actor, String nickName) throws Exception {
        return mockMvc.perform(patch("/account/nickName")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(actor))   // 이 레포는 Bearer 접두 없이 토큰만 싣는다
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickName\":\"" + nickName + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /* ════════════════ S — 통과 ════════════════ */

    @Test
    @DisplayName("S1: 한글·영문·숫자·밑줄 2~16자는 그대로 가입된다")
    void ordinaryNickNamesPass() throws Exception {
        assertThat(signUpStatus("김다이버")).isEqualTo(201);
        assertThat(signUpStatus("freediver_01")).isEqualTo(201);
    }

    /* ════════════════ V — 형식 거부 ════════════════ */

    @Test
    @DisplayName("V1: 1자 닉네임은 400 — 최소 2자")
    void tooShortIsRejected() throws Exception {
        assertThat(signUpStatus("x")).isEqualTo(400);
    }

    @Test
    @DisplayName("V2: 17자 이상 닉네임은 400 — 최대 16자")
    void tooLongIsRejected() throws Exception {
        assertThat(signUpStatus("abcdefghijklmnopq")).isEqualTo(400);
    }

    @Test
    @DisplayName("V3: 공백이 든 닉네임은 400 — URL 식별자를 겸하므로 띄어쓰기를 받지 않는다")
    void whitespaceIsRejected() throws Exception {
        assertThat(signUpStatus("유저의 닉네임")).isEqualTo(400);
    }

    @Test
    @DisplayName("V4: 자모 단독(ㅋㅋㅋ)·이모지·특수문자는 400 — 완성형 한글만 받는다")
    void jamoEmojiAndSymbolsAreRejected() throws Exception {
        assertThat(signUpStatus("ㅋㅋㅋ")).isEqualTo(400);
        assertThat(signUpStatus("🐬다이버")).isEqualTo(400);
        assertThat(signUpStatus("diver/pro")).isEqualTo(400);
    }

    @Test
    @DisplayName("V5: 키릴 'а' 를 섞은 'аdmin' 은 400 — 동형이의 문자 사칭은 예약어가 아니라 문자셋이 막는다")
    void homoglyphIsRejectedByCharset() throws Exception {
        assertThat(signUpStatus("аdmin")).isEqualTo(400);
    }

    @Test
    @DisplayName("V6: 형식 오류의 응답 msg 는 무엇이 틀렸는지 그대로 알려준다 — FE 가 표시할 수 있는 한국어")
    void formatErrorMessageIsUserFacing() throws Exception {
        mockMvc.perform(post("/sign/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpJson("x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("닉네임은")));
    }

    /* ════════════════ R — 예약어 거부 ════════════════ */

    @Test
    @DisplayName("R1: 브랜드명 '풍덩' 은 400 — 우리가 나중에 진짜 공식 계정에 쓰려고 막아 둔 이름")
    void brandNameIsReserved() throws Exception {
        assertThat(signUpStatus("풍덩")).isEqualTo(400);
    }

    @Test
    @DisplayName("R2: '풍덩공식' 처럼 브랜드명을 품은 이름도 400 — 사칭은 정확일치로 오지 않는다")
    void brandNameInsideIsReserved() throws Exception {
        assertThat(signUpStatus("풍덩공식")).isEqualTo(400);
        assertThat(signUpStatus("진짜풍덩")).isEqualTo(400);
    }

    @Test
    @DisplayName("R3: 구분자·숫자로 흘린 'p_u_n_g_d0ng' 도 400 — 정규화 후 판정한다")
    void evasionBySeparatorsAndLeetIsReserved() throws Exception {
        assertThat(signUpStatus("p_u_n_g_d0ng")).isEqualTo(400);
    }

    @Test
    @DisplayName("R4: '우리동네관리자' 처럼 운영자 역할어를 품은 이름은 400 — 운영자 사칭 안내가 안전 문제로 번진다")
    void koreanRoleWordInsideIsReserved() throws Exception {
        assertThat(signUpStatus("우리동네관리자")).isEqualTo(400);
        assertThat(signUpStatus("풍덩고객센터")).isEqualTo(400);
    }

    @Test
    @DisplayName("R5: 라틴 역할어는 정확일치·접두일 때 400 — 'admin_kim'")
    void latinRoleWordPrefixIsReserved() throws Exception {
        assertThat(signUpStatus("admin_kim")).isEqualTo(400);
        assertThat(signUpStatus("officialdiver")).isEqualTo(400);
    }

    @Test
    @DisplayName("R6: 라우트와 부딪히는 'public'·'suggested' 는 400 — 허용하면 그 계정 프로필이 영영 안 열린다")
    void routeCollidingNickNamesAreReserved() throws Exception {
        assertThat(signUpStatus("public")).isEqualTo(400);
        assertThat(signUpStatus("suggested")).isEqualTo(400);
    }

    @Test
    @DisplayName("R7: 예약어 응답은 어떤 단어가 예약어인지 알려주지 않는다 — 목록을 노출하면 우회 사전이 된다")
    void reservedMessageDoesNotLeakTheList() throws Exception {
        mockMvc.perform(post("/sign/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpJson("풍덩")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("사용할 수 없는 닉네임입니다."));
    }

    @Test
    @DisplayName("R8: 닉네임 변경(PATCH)도 같은 규칙 — 가입만 막고 변경으로 뚫리면 아무 의미가 없다")
    void nickNameUpdateIsGuardedToo() throws Exception {
        Account student = account("r8@example.com", "일반유저", Role.STUDENT);

        assertThat(patchNickNameStatus(student, "풍덩운영팀")).isEqualTo(400);
        assertThat(accountRepo.findById(student.getId()).orElseThrow().getNickName()).isEqualTo("일반유저");
    }

    /* ════════════════ F — 오탐 없음 ════════════════ */

    @Test
    @DisplayName("F1: 'badminton클럽' 은 통과한다 — 'admin' 부분일치로 막으면 멀쩡한 이름이 잡힌다")
    void innocentWordsContainingLatinTokensPass() throws Exception {
        assertThat(signUpStatus("badminton클럽")).isEqualTo(201);
    }

    @Test
    @DisplayName("F2: 'masterdiver' 는 통과한다 — 실제 다이빙 등급 표기라 접두 차단에서 뺐다(단독 'master' 만 막는다)")
    void divingRankWordsPass() throws Exception {
        assertThat(signUpStatus("masterdiver")).isEqualTo(201);
        assertThat(signUpStatus("master")).isEqualTo(400);
    }

    /* ════════════════ C — 중복확인 응답 ════════════════ */

    @Test
    @DisplayName("C1: 쓸 수 있는 닉네임은 200 {exists:false, available:true, reason:null}")
    void checkReturnsAvailable() throws Exception {
        mockMvc.perform(get("/sign/check/nickName").param("nickName", "freediver_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("C2: 이미 쓰는 닉네임은 에러가 아니라 200 {exists:true, available:false, reason:DUPLICATED}")
    void checkReturnsDuplicated() throws Exception {
        account("c2@example.com", "takennick", Role.STUDENT);

        mockMvc.perform(get("/sign/check/nickName").param("nickName", "takennick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATED"));
    }

    @Test
    @DisplayName("C3: 예약어는 아무도 안 쓰므로 exists:false 지만 available:false — exists 만 보면 초록불 켜고 가입에서 400 을 맞는다")
    void checkReturnsReservedEvenWhenNobodyHasIt() throws Exception {
        mockMvc.perform(get("/sign/check/nickName").param("nickName", "풍덩공식"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("RESERVED"));
    }

    @Test
    @DisplayName("C4: 형식 위반도 400 이 아니라 200 {available:false, reason:FORMAT} — 중복확인은 질의라 기대된 부정 답을 정상 응답으로 준다")
    void checkReturnsFormat() throws Exception {
        mockMvc.perform(get("/sign/check/nickName").param("nickName", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("FORMAT"));
    }

    /* ════════════════ A — 어드민 예외 ════════════════ */

    @Test
    @DisplayName("A1: 어드민은 예약어를 닉네임으로 쓸 수 있다 — 막아 둔 목적 자체가 '우리가 나중에 쓰려고' 다")
    void adminCanTakeReservedNickName() throws Exception {
        Account admin = account("a1@example.com", "운영담당", Role.ADMIN);

        assertThat(patchNickNameStatus(admin, "풍덩")).isEqualTo(200);
        assertThat(accountRepo.findById(admin.getId()).orElseThrow().getNickName()).isEqualTo("풍덩");
    }

    @Test
    @DisplayName("A2: 어드민이라도 형식은 지켜야 한다 — URL 식별자라 공백·특수문자는 어드민 계정이라고 안전해지지 않는다")
    void adminStillObeysFormat() throws Exception {
        Account admin = account("a2@example.com", "운영담당", Role.ADMIN);

        assertThat(patchNickNameStatus(admin, "풍덩 공식")).isEqualTo(400);
    }
}
