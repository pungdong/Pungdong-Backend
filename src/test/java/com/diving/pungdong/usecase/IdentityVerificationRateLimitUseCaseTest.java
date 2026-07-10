package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.config.EmbeddedRedisConfig;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본인확인 SMS <b>발송 쿨다운</b> use-case — 계정당 발송 간 최소 간격(다날 실 SMS 비용·남용 방어).
 * 기본 프로파일은 쿨다운 0(비활성)이라 여기서만 켠다({@code send-cooldown-seconds=30}). 쿨다운은 Redis
 * 창이라 embedded Redis 필요.
 *
 * <p><b>읽는 법</b>: RL1 재발송 차단, RL2 재-발송(create) 차단. 차단은 예외가 아니라
 * <b>200 + retryAfterSeconds</b>(정상 분기 — FE 가 "N초 후 재시도"로 렌더). SMS 미발송·상태 불변.
 */
@SpringBootTest(properties = "pungdong.identity-verification.send-cooldown-seconds=30")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisConfig.class)
class IdentityVerificationRateLimitUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired IdentityVerificationJpaRepo identityVerificationRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanUp() {
        identityVerificationRepo.deleteAll();
        accountRepo.deleteAll();
        Set<String> keys = redisTemplate.keys("identity:otp:cooldown:*"); // 쿨다운 창을 비워 테스트 순서 독립
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("RL1: 발송 직후 재발송하면 200 + retryAfterSeconds(미발송) — 레코드는 1건·상태 불변")
    void resendWithinCooldownReturnsRetryAfter() throws Exception {
        Account student = createStudent("rl1@test.com", "diverRL1");
        String token = tokenFor(student);
        long id = firstSend(token); // 첫 발송 = 쿨다운 창 획득

        mockMvc.perform(post("/identity-verifications/" + id + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retryAfterSeconds").value(greaterThan(0)))
                .andExpect(jsonPath("$.otpExpiresInSeconds").doesNotExist()); // 미발송

        assertThat(identityVerificationRepo.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("RL2: 쿨다운 창 안에서 새 발송(create)하면 200 + retryAfterSeconds — 새 레코드 안 생김")
    void secondCreateWithinCooldownBlocked() throws Exception {
        Account student = createStudent("rl2@test.com", "diverRL2");
        String token = tokenFor(student);
        firstSend(token);

        mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("한어진")))
                .andExpect(status().isOk()) // 성공 발송은 201, 쿨다운은 200
                .andExpect(jsonPath("$.retryAfterSeconds").value(greaterThan(0)))
                .andExpect(jsonPath("$.verificationId").doesNotExist());

        assertThat(identityVerificationRepo.findAll()).hasSize(1); // 두 번째는 미생성
    }

    /* ─── fixtures ─── */

    private long firstSend(String token) throws Exception {
        MvcResult r = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("한어진")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.otpExpiresInSeconds").value(greaterThan(0)))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("verificationId").asLong();
    }

    private Account createStudent(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String createBody(String realName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("realName", realName);
        body.put("birth", "19980914");
        body.put("gender", "MALE");
        body.put("phoneNumber", "010-1234-5678");
        body.put("carrier", "SKT");
        body.put("method", "SMS");
        body.put("agreedRequiredTerms", true);
        return objectMapper.writeValueAsString(body);
    }
}
