package com.diving.pungdong.usecase;

import com.diving.pungdong.config.EmbeddedRedisConfig;
import com.diving.pungdong.config.RestDocsConfiguration;
import com.diving.pungdong.config.security.JwtTokenProvider;
import com.diving.pungdong.config.security.UserAccount;
import com.diving.pungdong.domain.account.Account;
import com.diving.pungdong.domain.account.Role;
import com.diving.pungdong.dto.account.emailCheck.EmailResult;
import com.diving.pungdong.service.InstructorCertificateService;
import com.diving.pungdong.service.account.AccountService;
import com.diving.pungdong.service.kafka.AccountKafkaProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import({RestDocsConfiguration.class, EmbeddedRedisConfig.class})
class AuthUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @Value("${spring.jwt.secret}")
    String rawSecret;

    @MockBean AccountService accountService;
    @MockBean InstructorCertificateService instructorCertificateService;
    @MockBean AccountKafkaProducer accountKafkaProducer;

    private String encodedKey() {
        return Base64.getEncoder().encodeToString(rawSecret.getBytes());
    }

    private Account stubAccount(long id, Role role) {
        Account a = Account.builder()
                .id(id)
                .email(id + "@test.com")
                .password("encoded")
                .nickName("user" + id)
                .roles(Set.of(role))
                .build();
        given(accountService.loadUserByUsername(String.valueOf(id)))
                .willReturn(new UserAccount(a));
        return a;
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String expiredToken(Account a) {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        Claims claims = Jwts.claims();
        claims.put("user_name", String.valueOf(a.getId()));
        claims.put("roles", a.getRoles());
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date(past.getTime() - 60_000))
                .setExpiration(past)
                .signWith(SignatureAlgorithm.HS256, encodedKey())
                .compact();
    }

    private String tokenWithWrongSignature(Account a) {
        String wrongKey = Base64.getEncoder().encodeToString("wrong_secret_key".getBytes());
        Claims claims = Jwts.claims();
        claims.put("user_name", String.valueOf(a.getId()));
        claims.put("roles", a.getRoles());
        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 60_000))
                .signWith(SignatureAlgorithm.HS256, wrongKey)
                .compact();
    }

    @Test
    @DisplayName("T1: 만료된 access token으로 보호된 API 호출 시 인증 실패 처리됨")
    void expiredToken_isRejected() throws Exception {
        Account student = stubAccount(1L, Role.STUDENT);
        String expired = expiredToken(student);

        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/entrypoint"));
    }

    @Test
    @DisplayName("T2: 다른 키로 서명된 access token은 인증 실패 처리됨")
    void wrongSignatureToken_isRejected() throws Exception {
        Account student = stubAccount(1L, Role.STUDENT);
        String wrong = tokenWithWrongSignature(student);

        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, wrong)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/entrypoint"));
    }

    @Test
    @DisplayName("T3: Authorization 헤더 없이 보호된 API 호출 시 인증 실패 처리됨")
    void missingAuthHeader_isRejected() throws Exception {
        mockMvc.perform(post("/sign/firebase-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/entrypoint"));
    }

    @Test
    @DisplayName("T4: 형식이 깨진 문자열을 토큰으로 보내면 인증 실패 처리됨")
    void malformedToken_isRejected() throws Exception {
        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, "not.a.valid.jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/entrypoint"));
    }

    @Test
    @DisplayName("R1: STUDENT 토큰으로 ADMIN 전용 API 호출 시 권한 부족으로 거부됨")
    void studentAccessingAdminEndpoint_isForbidden() throws Exception {
        Account student = stubAccount(1L, Role.STUDENT);
        String token = tokenFor(student);

        mockMvc.perform(get("/sign/instructor/request/list")
                .header(HttpHeaders.AUTHORIZATION, token)
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/accessDenied"));
    }

    @Test
    @DisplayName("R2: STUDENT 토큰으로 INSTRUCTOR 전용 API 호출 시 권한 부족으로 거부됨")
    void studentAccessingInstructorEndpoint_isForbidden() throws Exception {
        Account student = stubAccount(1L, Role.STUDENT);
        String token = tokenFor(student);

        mockMvc.perform(get("/account/instructor/certificate/list")
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/exception/accessDenied"));
    }

    @Test
    @DisplayName("R3: INSTRUCTOR 토큰으로 INSTRUCTOR 전용 API 호출 시 통과됨")
    void instructorAccessingInstructorEndpoint_succeeds() throws Exception {
        Account instructor = stubAccount(1L, Role.INSTRUCTOR);
        String token = tokenFor(instructor);

        given(instructorCertificateService.findInstructorCertificates(any()))
                .willReturn(Collections.emptyList());
        given(instructorCertificateService.mapToInstructorCertificateInfos(any()))
                .willReturn(Collections.emptyList());

        mockMvc.perform(get("/account/instructor/certificate/list")
                .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P1: 토큰 없이도 public 엔드포인트(/sign/check/email)는 200 응답")
    void publicEndpoint_acceptsRequestWithoutToken() throws Exception {
        given(accountService.checkEmailExistence(any()))
                .willReturn(EmailResult.builder().existed(false).build());

        mockMvc.perform(post("/sign/check/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@y.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("J1: 발급된 access token이 user_name/roles/exp/iat 클레임과 HS256 서명을 가짐")
    void accessTokenClaimsAndAlgorithm_areInvariant() {
        String userPk = "42";
        Set<Role> roles = Set.of(Role.STUDENT);

        String token = jwtTokenProvider.createAccessToken(userPk, roles);
        Jws<Claims> parsed = Jwts.parser()
                .setSigningKey(encodedKey())
                .parseClaimsJws(token);

        Claims body = parsed.getBody();
        assertThat(body.get("user_name", String.class)).isEqualTo(userPk);
        assertThat(body.get("roles")).isNotNull();
        assertThat(body.getIssuedAt()).isNotNull();
        assertThat(body.getExpiration()).isAfter(body.getIssuedAt());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    @DisplayName("L1: 로그아웃 후에도 같은 access token으로 보호된 API 통과 — 블랙리스트 미작동, 현재 동작 캡처")
    void logoutDoesNotInvalidateToken_currentBehavior() throws Exception {
        Account student = stubAccount(1L, Role.STUDENT);
        String accessToken = tokenFor(student);
        String refreshToken = jwtTokenProvider.createRefreshToken(String.valueOf(student.getId()));

        String logoutBody = objectMapper.writeValueAsString(
                java.util.Map.of("accessToken", accessToken, "refreshToken", refreshToken));
        mockMvc.perform(post("/sign/logout")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(logoutBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isNoContent());
    }
}
