package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AuthProvider;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.account.dto.signUp.SignUpInfo;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원가입 흐름 use-case 시나리오.
 * <p>
 * 컨트롤러부터 DB까지 통째로 묶어서 실제 HTTP → Spring Security 필터 → 서비스 → JPA 까지
 * 일관된 시나리오로 검증한다. {@link com.diving.pungdong.account.SignControllerTest}
 * 가 HTTP 와 REST Docs 만 검증하는 반면, 이 테스트는 "어떤 입력에 대해 어떤 사용자 상태가
 * 생기는가" 를 검증하는 안전망 역할.
 * <p>
 * <b>읽는 법</b>: {@code @DisplayName} 의 한글 시나리오를 위에서 아래로 읽으면 회원가입 사양이
 * 그대로 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignUpUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private String json(SignUpInfo info) throws Exception {
        return objectMapper.writeValueAsString(info);
    }

    @Test
    @DisplayName("S1: 이메일/비밀번호/닉네임만 보내면 201 + 새 STUDENT 계정이 DB에 저장됨 + 토큰 동시 발급")
    void signUp_succeeds_withMinimalPayload() throws Exception {
        SignUpInfo payload = SignUpInfo.builder()
                .email("yechan@example.com")
                .password("plain-password-1234")
                .nickName("yechan")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("yechan@example.com"))
                .andExpect(jsonPath("$.nickName").value("yechan"))
                .andExpect(jsonPath("$.tokens.access_token").exists())
                .andExpect(jsonPath("$.tokens.refresh_token").exists())
                .andExpect(jsonPath("$.tokens.token_type").value("bearer"));

        Account saved = accountRepo.findByEmail("yechan@example.com").orElseThrow();
        assertThat(saved.getNickName()).isEqualTo("yechan");
        assertThat(saved.getRoles()).containsExactly(Role.STUDENT);
        assertThat(saved.getProvider()).isEqualTo(AuthProvider.EMAIL);
        assertThat(saved.getSocialId()).isNull();
        assertThat(saved.getIsDeleted()).isFalse();
    }

    @Test
    @DisplayName("S2: 비밀번호는 평문이 아닌 BCrypt 해시로 저장되고, 원본과 매치된다")
    void signUp_storesPasswordAsHash() throws Exception {
        String rawPassword = "plain-password-1234";
        SignUpInfo payload = SignUpInfo.builder()
                .email("hash@example.com")
                .password(rawPassword)
                .nickName("hashuser")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
                .andExpect(status().isCreated());

        Account saved = accountRepo.findByEmail("hash@example.com").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, saved.getPassword())).isTrue();
    }

    @Test
    @DisplayName("S3: 가입 시 birth/gender/phoneNumber 는 받지 않으므로 DB 에 null 로 남는다 — 본인인증 게이트로 분리")
    void signUp_leavesPersonalFieldsNull() throws Exception {
        SignUpInfo payload = SignUpInfo.builder()
                .email("minimal@example.com")
                .password("pw1234")
                .nickName("minimal")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
                .andExpect(status().isCreated());

        Account saved = accountRepo.findByEmail("minimal@example.com").orElseThrow();
        assertThat(saved.getBirth()).isNull();
        assertThat(saved.getGender()).isNull();
        assertThat(saved.getPhoneNumber()).isNull();
    }

    @Test
    @DisplayName("S4: 가입 직후 기본 프로필 사진이 자동으로 연결되어 있다")
    void signUp_attachesDefaultProfilePhoto() throws Exception {
        SignUpInfo payload = SignUpInfo.builder()
                .email("photo@example.com")
                .password("pw1234")
                .nickName("photo-user")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
                .andExpect(status().isCreated());

        Account saved = accountRepo.findByEmail("photo@example.com").orElseThrow();
        assertThat(saved.getProfilePhoto()).isNotNull();
        assertThat(saved.getProfilePhoto().getId()).isNotNull();
    }

    @Test
    @DisplayName("V1: 이메일 형식이 잘못되면 가입은 거부되고 DB 에 아무 행도 안 생긴다")
    void signUp_rejectsInvalidEmail() throws Exception {
        SignUpInfo payload = SignUpInfo.builder()
                .email("not-an-email")
                .password("pw1234")
                .nickName("bademail")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
                .andExpect(status().is4xxClientError());

        assertThat(accountRepo.findByNickName("bademail")).isEmpty();
    }

    @Test
    @DisplayName("V2: 이메일/비밀번호/닉네임 중 하나라도 비면 가입 거부")
    void signUp_rejectsMissingFields() throws Exception {
        SignUpInfo missingPassword = SignUpInfo.builder()
                .email("a@example.com")
                .password("")
                .nickName("nopw")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(missingPassword)))
                .andExpect(status().is4xxClientError());

        assertThat(accountRepo.findByNickName("nopw")).isEmpty();
    }

    @Test
    @DisplayName("V3: check/email 에 잘못된 이메일 형식이면 400 + 필드 메시지 (존재 조회 이전에 거부)")
    void checkEmail_rejectsInvalidFormat() throws Exception {
        mockMvc.perform(post("/sign/check/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("이메일 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("D1: 동일 이메일로 두 번째 가입 시도 시 거부 — 첫 번째 계정만 살아남는다")
    void signUp_rejectsDuplicateEmail() throws Exception {
        SignUpInfo first = SignUpInfo.builder()
                .email("dup@example.com")
                .password("pw1234")
                .nickName("first-user")
                .build();
        SignUpInfo second = SignUpInfo.builder()
                .email("dup@example.com")
                .password("pw5678")
                .nickName("second-user")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(second)))
                .andExpect(status().is4xxClientError());

        assertThat(accountRepo.findAll()).hasSize(1);
        assertThat(accountRepo.findByEmail("dup@example.com").orElseThrow().getNickName())
                .isEqualTo("first-user");
    }

    @Test
    @DisplayName("D2: 동일 닉네임으로 두 번째 가입 시도 시 거부 — 닉네임은 가입 단계에서 unique")
    void signUp_rejectsDuplicateNickName() throws Exception {
        SignUpInfo first = SignUpInfo.builder()
                .email("nick1@example.com")
                .password("pw1234")
                .nickName("samenick")
                .build();
        SignUpInfo second = SignUpInfo.builder()
                .email("nick2@example.com")
                .password("pw5678")
                .nickName("samenick")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(second)))
                .andExpect(status().is4xxClientError());

        assertThat(accountRepo.findAll()).hasSize(1);
        assertThat(accountRepo.findByEmail("nick2@example.com")).isEmpty();
    }

    @Test
    @DisplayName("D3: 닉네임 중복확인 — 사용 가능한 닉네임은 200 {exists:false}")
    void checkNickName_returnsFalse_whenAvailable() throws Exception {
        mockMvc.perform(get("/sign/check/nickName").param("nickName", "freebie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    @DisplayName("D4: 닉네임 중복확인 — 이미 쓰는 닉네임은 에러가 아니라 200 {exists:true}")
    void checkNickName_returnsTrue_whenTaken() throws Exception {
        SignUpInfo signUp = SignUpInfo.builder()
                .email("taken@example.com")
                .password("pw1234")
                .nickName("takennick")
                .build();
        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(signUp)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/sign/check/nickName").param("nickName", "takennick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    @DisplayName("L1: 가입 응답에 토큰이 같이 와서 별도 로그인 호출 없이 인증 상태로 진입 — 같은 자격으로 다시 /sign/login 도 동일하게 토큰 발급")
    void signUp_returnsTokens_andCanRelogin() throws Exception {
        SignUpInfo signUp = SignUpInfo.builder()
                .email("login@example.com")
                .password("pw1234")
                .nickName("loginuser")
                .build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(signUp)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokens.access_token").exists())
                .andExpect(jsonPath("$.tokens.refresh_token").exists())
                .andExpect(jsonPath("$.tokens.token_type").value("bearer"));

        // 가입 후 같은 자격으로 명시적 로그인도 정상 — 다른 디바이스/세션 진입 시나리오
        String loginBody = "{\"email\":\"login@example.com\",\"password\":\"pw1234\"}";
        mockMvc.perform(post("/sign/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.refresh_token").exists());
    }
}
