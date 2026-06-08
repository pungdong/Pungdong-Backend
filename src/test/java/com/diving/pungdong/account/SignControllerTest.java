package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.exception.CEmailSigninFailedException;
import com.diving.pungdong.global.config.RestDocsConfiguration;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.global.security.UserAccount;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.domain.lecture.Organization;
import com.diving.pungdong.account.dto.emailCheck.EmailInfo;
import com.diving.pungdong.account.dto.emailCheck.EmailResult;
import com.diving.pungdong.account.dto.FirebaseTokenDto;
import com.diving.pungdong.account.dto.nickNameCheck.NickNameResult;
import com.diving.pungdong.account.dto.signIn.SignInInfo;
import com.diving.pungdong.account.dto.signUp.SignUpInfo;
import com.diving.pungdong.account.dto.signUp.SignUpResult;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.account.AccountService;
import com.diving.pungdong.account.InstructorCertificateService;
import com.diving.pungdong.account.FirebaseTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.diving.pungdong.account.SignController.LogoutReq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.partWithName;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import(RestDocsConfiguration.class)
class SignControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    RedisTemplate<String, String> redisTemplate;

    /** /sign/logout 가 redis 에 남긴 블랙리스트 토큰이 같은 user id (1L) 를 쓰는 다른 테스트로 새지 않도록 매 테스트 후 flush. */
    @AfterEach
    void flushRedisBlacklist() {
        redisTemplate.execute((RedisConnection conn) -> {
            conn.flushDb();
            return null;
        });
    }

    @MockBean
    AccountService accountService;

    @MockBean
    InstructorCertificateService instructorCertificateService;

    @MockBean
    FirebaseTokenService firebaseTokenService;

    public Account createAccount(Role role) {
        Account account = Account.builder()
                .id(1L)
                .email("yechan@gmail.com")
                .password("1234")
                .nickName("yechan")
                .birth("1999-09-11")
                .gender(Gender.MALE)
                .roles(Set.of(role))
                .build();

        given(accountService.loadUserByUsername(String.valueOf(account.getId())))
                .willReturn(new UserAccount(account));

        return account;
    }

    private Collection<? extends GrantedAuthority> authorities(Set<Role> roles) {
        return roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("이메일 존재 여부 확인")
    public void checkEmailExistence() throws Exception {
        EmailInfo emailInfo = EmailInfo.builder()
                .email("kim@gmail.com")
                .build();

        EmailResult emailResult = EmailResult.builder()
                .exists(true)
                .build();

        given(accountService.checkEmailExistence(any())).willReturn(emailResult);

        mockMvc.perform(post("/sign/check/email")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .content(objectMapper.writeValueAsString(emailInfo)))
                .andDo(print())
                .andExpect(status().isOk())
                .andDo(
                        document("account-check-email",
                                requestHeaders(
                                        headerWithName(HttpHeaders.CONTENT_TYPE).description("JSON 타입")
                                ),
                                requestFields(
                                        fieldWithPath("email").description("유저 이메일")
                                ),
                                responseFields(
                                        fieldWithPath("exists").description("유저 이메일 존재 여부"),
                                        fieldWithPath("_links.self.href").description("해당 API 링크"),
                                        fieldWithPath("_links.profile.href").description("API 문서 링크")
                                )
                        )
                );
    }

    @Test
    @DisplayName("닉네임 중복 여부 확인")
    public void checkDuplicationNickName() throws Exception {
        String nickName = "닉네임";
        NickNameResult nickNameResult = NickNameResult.builder()
                .exists(false)
                .build();

        given(accountService.checkNickNameExistence(nickName)).willReturn(nickNameResult);

        mockMvc.perform(get("/sign/check/nickName")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .param("nickName", nickName))
                .andDo(print())
                .andExpect(status().isOk())
                .andDo(
                        document("account-check-duplication-nickName",
                                requestHeaders(
                                        headerWithName(HttpHeaders.CONTENT_TYPE).description("JSON 타입")
                                ),
                                requestParameters(
                                        parameterWithName("nickName").description("닉네임")
                                ),
                                responseFields(
                                        fieldWithPath("exists").description("유저 닉네임 존재 여부"),
                                        fieldWithPath("_links.self.href").description("해당 API 링크"),
                                        fieldWithPath("_links.profile.href").description("API 문서 링크")
                                )
                        )
                );
    }

    @Test
    @DisplayName("회원가입 성공 - 가입과 동시에 access/refresh 토큰이 함께 반환됨 (auto-login)")
    public void signupInstructorSuccess() throws Exception {
        SignUpInfo signUpInfo = SignUpInfo.builder()
                .email("yechan@gmail.com")
                .password("1234")
                .nickName("yechan")
                .build();

        Account saved = Account.builder()
                .id(1L)
                .email(signUpInfo.getEmail())
                .nickName(signUpInfo.getNickName())
                .roles(java.util.Set.of(Role.STUDENT))
                .build();
        given(accountService.saveAccountInfo(signUpInfo)).willReturn(saved);

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaTypes.HAL_JSON)
                .content(objectMapper.writeValueAsString(signUpInfo)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("email").value(signUpInfo.getEmail()))
                .andExpect(jsonPath("nickName").value(signUpInfo.getNickName()))
                .andExpect(jsonPath("tokens.access_token").exists())
                .andExpect(jsonPath("tokens.refresh_token").exists())
                .andExpect(jsonPath("tokens.token_type").value("bearer"))
                .andDo(document("signUp",
                        requestHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("JSON 타입")
                        ),
                        requestFields(
                                fieldWithPath("email").description("유저 ID (이메일)"),
                                fieldWithPath("password").description("유저 PASSWORD"),
                                fieldWithPath("nickName").description("유저의 닉네임")
                        ),
                        responseHeaders(
                                headerWithName(HttpHeaders.LOCATION).description("API 주소"),
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("HAL JSON 타입")
                        ),
                        responseFields(
                                fieldWithPath("email").description("유저 ID"),
                                fieldWithPath("nickName").description("유저의 닉네임"),
                                fieldWithPath("tokens.access_token").description("Access token (1h)"),
                                fieldWithPath("tokens.refresh_token").description("Refresh token (30h)"),
                                fieldWithPath("tokens.token_type").description("토큰 타입 (bearer)"),
                                fieldWithPath("tokens.expires_in").description("access_token 만료 (초)"),
                                fieldWithPath("tokens.scope").description("권한 범위"),
                                fieldWithPath("tokens.jti").description("토큰 식별자 (UUID)"),
                                fieldWithPath("_links.self.href").description("해당 API 링크"),
                                fieldWithPath("_links.profile.href").description("API 문서 링크")
                        )
                ));
    }

    @Test
    @DisplayName("회원 가입 실패 - 입력값이 잘못됨")
    public void signupInputNull() throws Exception {
        SignUpInfo signUpInfo = SignUpInfo.builder().build();

        mockMvc.perform(post("/sign/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaTypes.HAL_JSON)
                .content(objectMapper.writeValueAsString(signUpInfo)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(-1004))
                .andExpect(jsonPath("success").value(false));
    }

    @Test
    @DisplayName("로그인 성공")
    public void loginSuccess() throws Exception {
        String email = "yechan@gmail.com";
        String password = "1234";

        SignInInfo signInInfo = new SignInInfo(email, password);

        Account account = Account.builder()
                .id(1L)
                .email("yechan@gmail.com")
                .password(passwordEncoder.encode(password))
                .nickName("yechan")
                .birth("1999-09-11")
                .gender(Gender.MALE)
                .roles(Set.of(Role.STUDENT))
                .build();

        given(accountService.findAccountByEmail(signInInfo.getEmail())).willReturn(account);

        mockMvc.perform(post("/sign/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInInfo)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("access_token").exists())
                .andExpect(jsonPath("_links.self").exists())
                .andDo(document("signIn",
                        requestHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("JSON 타입")
                        ),
                        requestFields(
                                fieldWithPath("email").description("유저 ID"),
                                fieldWithPath("password").description("유저 PASSWORD")
                        ),
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("HAL JSON 타입")
                        ),
                        responseFields(
                                fieldWithPath("access_token").description("JWT 인증 토큰 값"),
                                fieldWithPath("refresh_token").description("JWT Refresh Token 값"),
                                fieldWithPath("token_type").description("토큰 타입"),
                                fieldWithPath("scope").description("토큰 권한 범위"),
                                fieldWithPath("expires_in").description("유효 기간"),
                                fieldWithPath("jti").description("JWT 토큰 식별자"),
                                fieldWithPath("_links.self.href").description("해당 API 링크"),
                                fieldWithPath("_links.profile.href").description("API 문서 링크")
                        )
                ));
    }

    @Test
    @DisplayName("로그인 실패 - 이메일(ID)이 없는 경우")
    public void loginNotFoundEmail() throws Exception {
        String email = "yechan@gmail.com";
        String password = "1234";

        doThrow(new CEmailSigninFailedException()).when(accountService).findAccountByEmail(email);

        mockMvc.perform(post("/sign/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignInInfo(email, password))))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("success").value(false))
                .andExpect(jsonPath("code").value(-1001));
    }

    @Test
    @DisplayName("로그인 실패 - PASSWORD가 틀린 경우")
    public void loginNotMatchPassword() throws Exception {
        String email = "yechan@gmail.com";
        String password = "1234";
        String encodedPassword = passwordEncoder.encode(password);

        Account account = Account.builder()
                .email(email)
                .password(encodedPassword)
                .build();

        given(accountService.findAccountByEmail(email)).willReturn(account);
        doThrow(new CEmailSigninFailedException()).when(accountService).checkCorrectPassword(any(), any());

        mockMvc.perform(post("/sign/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignInInfo(email, "wrongPassword"))))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("success").value(false))
                .andExpect(jsonPath("code").value(-1001));
    }

    @Test
    @DisplayName("firebase token 등록")
    public void enrollFirebaseToken() throws Exception {
        Account account = createAccount(Role.STUDENT);
        String accessToken = jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());

        FirebaseTokenDto firebaseTokenDto = FirebaseTokenDto.builder()
                .token("abcd12341234")
                .build();

        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(firebaseTokenDto)))
                .andDo(print())
                .andExpect(status().isNoContent())
                .andDo(
                        document(
                                "sign-enroll-firebase-token",
                                requestHeaders(
                                        headerWithName(HttpHeaders.CONTENT_TYPE).description("JSON 타입"),
                                        headerWithName(HttpHeaders.AUTHORIZATION).description("access token 값")
                                ),
                                requestFields(
                                        fieldWithPath("token").description("firebase token 값")
                                )
                        )
                );
    }


    @Test
    @DisplayName("로그아웃 성공")
    public void logout() throws Exception {
        Account account = Account.builder()
                .id(1L)
                .email("yechan@gmail.com")
                .password("1234")
                .roles(Set.of(Role.INSTRUCTOR))
                .build();

        given(accountService.loadUserByUsername(String.valueOf(account.getId())))
                .willReturn(new User(account.getEmail(), account.getPassword(), authorities(account.getRoles())));

        String accessToken = jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
        String refreshToken = jwtTokenProvider.createRefreshToken(String.valueOf(account.getId()));

        LogoutReq logoutReq = LogoutReq.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/sign/logout")
                .header("Authorization", accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(logoutReq)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("message").exists())
                .andDo(document("logout",
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("access token 값")),
                        requestFields(
                                fieldWithPath("accessToken").description("access token 값"),
                                fieldWithPath("refreshToken").description("refresh token 값")),
                        responseFields(
                                fieldWithPath("message").description("성공 메세지"),
                                fieldWithPath("_links.self.href").description("해당 API 주소"),
                                fieldWithPath("_links.profile.href").description("해당 API 문서 링크")
                        )
                ));
    }

}