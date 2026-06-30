package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.ForeignerType;
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본인확인(identity-verification) 도메인 use-case 시나리오.
 *
 * <p>본인확인은 <b>계정 공유 자산</b> — 수강/강사 어느 플로우에서 만들었든 같은 레코드를 가리킨다.
 * 강사 신청은 GET /me 로 기존 인증을 확인해 재인증을 건너뛴다(skip). 본인확인은 stub
 * ({@link com.diving.pungdong.identityverification.StubIdentityVerifier})라 즉시 verified.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래로 읽으면 사양. I* = identity 본체.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityVerificationUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired IdentityVerificationJpaRepo identityVerificationRepo;

    @AfterEach
    void cleanUp() {
        identityVerificationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private Account createStudent(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT)))
                .build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String identityBody(String realName) {
        Map<String, Object> body = new HashMap<>();
        body.put("realName", realName);
        body.put("birth", "19980914");
        body.put("gender", "MALE");
        body.put("phoneNumber", "010-1234-5678");
        body.put("provider", "KAKAO");
        body.put("agreedRequiredTerms", true);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long verify(String token, String realName) throws Exception {
        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identityBody(realName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("verificationId").asLong();
    }

    @Test
    @DisplayName("I1: 본인확인 후 GET /me 로 조회하면 verified=true + verificationId + provider + verifiedAt 이 보인다")
    void verifyThenGetMe() throws Exception {
        Account student = createStudent("i1@test.com", "diverI1");
        String token = tokenFor(student);
        long verificationId = verify(token, "한어진");

        mockMvc.perform(get("/identity-verifications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.verificationId").value((int) verificationId))
                .andExpect(jsonPath("$.realName").value("한어진"))
                .andExpect(jsonPath("$.provider").value("KAKAO"))
                .andExpect(jsonPath("$.verifiedAt").exists());
    }

    @Test
    @DisplayName("I2: 본인확인 이력이 없는 사용자가 GET /me 를 호출하면 200 {verified:false} (404 아님)")
    void getMe_whenNeverVerified() throws Exception {
        Account student = createStudent("i2@test.com", "diverI2");

        mockMvc.perform(get("/identity-verifications/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.verificationId").doesNotExist());
    }

    @Test
    @DisplayName("I3: 같은 계정이 여러 번 인증하면 GET /me 는 최신 1건을 돌려준다 (계정 공유 자산, latest)")
    void getMe_returnsLatest() throws Exception {
        Account student = createStudent("i3@test.com", "diverI3");
        String token = tokenFor(student);
        verify(token, "이전이름");
        long latest = verify(token, "최신이름");

        mockMvc.perform(get("/identity-verifications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value((int) latest))
                .andExpect(jsonPath("$.realName").value("최신이름"));

        assertThat(identityVerificationRepo.findAll()).hasSize(2); // 이력은 보존
    }

    @Test
    @DisplayName("I4: 본인확인 시 통신사·내외국인 구분이 저장된다 (처리방침 수집 항목 ↔ 저장 컬럼 1:1)")
    void verifyPersistsCarrierAndForeignerType() throws Exception {
        Account student = createStudent("i4@test.com", "diverI4");
        long id = verify(tokenFor(student), "한어진");

        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getCarrier()).isNotBlank();                 // 기관 반환 속성(stub mock)
        assertThat(saved.getForeignerType()).isEqualTo(ForeignerType.DOMESTIC);
        assertThat(saved.getPhoneNumber()).isEqualTo("010-1234-5678");
    }
}
