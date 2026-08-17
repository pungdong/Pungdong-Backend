package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.ota.AppPolicyJpaRepo;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 앱 최소버전 게이트 정책(`GET /app/policy`, `PUT /admin/app/policy`) 실행 스펙.
 *
 * <p>@DisplayName 을 위에서 아래로 읽으면 계약이 드러난다. 핵심 두 가지:
 * <b>(1) 정책이 없으면 전 버전을 통과시킨다</b> — 게이트가 사용자를 앱 밖에 가두면 안 되므로 fail-safe 방향이
 * 런칭 게이트와 <b>반대</b>다. <b>(2) 이 엔드포인트는 어떤 경우에도 401 을 내지 않는다</b> — 앱이 공개
 * 엔드포인트도 같은 axios 인스턴스(토큰 동봉)로 부르므로, 401 이 한 번이라도 나오면 그 인터셉터가 부팅 중에
 * 정상 사용자를 로그아웃시킨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppPolicyUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired AppPolicyJpaRepo appPolicyJpaRepo;

    @Value("${spring.jwt.secret}")
    String jwtSecret;

    @AfterEach
    void cleanUp() {
        appPolicyJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
    }

    @Test
    @DisplayName("P1: 정책을 설정하지 않았으면 최소 버전 0.0.0 — 게이트가 아무도 가두지 않는다")
    void unset_passesEveryVersion() throws Exception {
        mockMvc.perform(get("/app/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ios.minVersion").value("0.0.0"))
                .andExpect(jsonPath("$.android.minVersion").value("0.0.0"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("P2: 로그인하지 않아도 정책을 조회할 수 있다")
    void anonymous_canRead() throws Exception {
        mockMvc.perform(get("/app/policy")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("P3: 만료된 토큰을 달고 와도 200 — 부팅 중 정책 조회가 사용자를 로그아웃시키면 안 된다")
    void expiredToken_stillReturnsOk() throws Exception {
        mockMvc.perform(get("/app/policy").header(HttpHeaders.AUTHORIZATION, expiredToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ios.minVersion").exists());

        // 형식 자체가 깨진 토큰도 마찬가지.
        mockMvc.perform(get("/app/policy").header(HttpHeaders.AUTHORIZATION, "not.a.jwt"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P4: 어드민이 잘못된 형식의 버전을 저장하려 하면 400 + 무엇이 틀렸는지 한국어로 알려준다")
    void adminUpdate_rejectsMalformedVersion() throws Exception {
        String body = "{\"ios\":{\"minVersion\":\"1.0\"},\"android\":{\"minVersion\":\"1.0.0\"},\"message\":null}";

        mockMvc.perform(put("/admin/app/policy").header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("최소 버전은 1.0.0 형식으로 입력해주세요."));
    }

    @Test
    @DisplayName("P5: 어드민이 정책을 저장하면 앱 조회에 그대로 반영된다")
    void adminUpdate_thenAppSeesIt() throws Exception {
        String body = "{\"ios\":{\"minVersion\":\"1.2.0\",\"latestVersion\":\"1.3.0\","
                + "\"storeUrl\":\"https://apps.apple.com/app/id1\"},"
                + "\"android\":{\"minVersion\":\"1.1.0\"},"
                + "\"message\":\"업데이트가 필요해요\"}";

        mockMvc.perform(put("/admin/app/policy").header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ios.minVersion").value("1.2.0"))
                .andExpect(jsonPath("$.ios.storeUrl").value("https://apps.apple.com/app/id1"))
                .andExpect(jsonPath("$.android.minVersion").value("1.1.0"))
                .andExpect(jsonPath("$.message").value("업데이트가 필요해요"));
    }

    @Test
    @DisplayName("P6: 어드민이 아니면 정책을 못 바꾼다 (403)")
    void nonAdmin_cannotUpdate() throws Exception {
        Account student = createAccount("p6@test.com", "p6user", Role.STUDENT);
        String body = "{\"ios\":{\"minVersion\":\"1.0.0\"},\"android\":{\"minVersion\":\"1.0.0\"}}";

        mockMvc.perform(put("/admin/app/policy").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    /* ─── 헬퍼 ────────────────────────────────────────────────────────────── */

    /** 서명은 유효하지만 만료된 토큰 — 필터가 인증을 안 채우고 permitAll 로 통과해야 한다. */
    private String expiredToken() {
        String encoded = Base64.getEncoder().encodeToString(jwtSecret.getBytes());
        long past = System.currentTimeMillis() - 60_000;
        return Jwts.builder()
                .claim("user_name", "1")
                .setIssuedAt(new Date(past - 60_000))
                .setExpiration(new Date(past))
                .signWith(SignatureAlgorithm.HS256, encoded)
                .compact();
    }

    private String adminToken() {
        Account admin = accountJpaRepo.findByEmail("policy-admin@test.com")
                .orElseGet(() -> createAccount("policy-admin@test.com", "policyadmin", Role.ADMIN));
        return tokenFor(admin);
    }

    private Account createAccount(String email, String nick, Role role) {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        return accountJpaRepo.save(Account.builder()
                .email(email).password(passwordEncoder.encode("1234")).nickName(nick)
                .phoneNumber("01012345678").birth("1990-01-01").gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(role))).profilePhoto(photo)
                .build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }
}
