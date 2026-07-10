package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.identityverification.Carrier;
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import com.diving.pungdong.identityverification.StubIdentityVerifier;
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
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본인확인(identity-verification) 도메인 use-case 시나리오 — 휴대폰 SMS 2단계(발송/확인).
 *
 * <p>본인확인은 <b>계정 공유 자산</b> — 수강/강사 어느 플로우에서 만들었든 같은 레코드를 가리킨다.
 * 강사 신청은 GET /me 로 기존 인증을 확인해 재인증을 건너뛴다(skip). 테스트는 stub
 * ({@link StubIdentityVerifier})라 문자 미발송 + 매직 OTP {@code "000000"}=성공.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래로 읽으면 사양.
 * S* = 성공, V* = 검증/실패, D* = 재발송, T* = 상태, R* = 권한.
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

    /** 하이픈 표기로 보낸다 — BE 가 정규화해 숫자만 저장/전송하는지까지 이 픽스처가 함께 검증한다(S4). */
    private String createBody(String realName) {
        return createBody(realName, "010-1234-5678", "19980914");
    }

    private String createBody(String realName, String phoneNumber, String birth) {
        Map<String, Object> body = new HashMap<>();
        body.put("realName", realName);
        body.put("birth", birth);
        body.put("gender", "MALE");
        body.put("phoneNumber", phoneNumber);
        body.put("carrier", "SKT");
        body.put("method", "SMS");
        body.put("agreedRequiredTerms", true);
        return write(body);
    }

    /**
     * 형식이 깨진 요청 — 400 이고, 레코드가 안 생겼다는 건 서비스/외부 기관까지 못 갔다는 뜻.
     * 응답 msg 는 <b>어느 필드가 왜 틀렸는지</b>(DTO 검증 메시지) 그대로 — 형식 규칙은 공개 계약이라 노출 OK.
     */
    private void expectRejectedBeforeSending(String token, String body, String expectedMsg) throws Exception {
        mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(expectedMsg));

        assertThat(identityVerificationRepo.findAll()).isEmpty();
    }

    private String otpBody(String otp) {
        return write(Map.of("otp", otp));
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 생성(=발송) → READY. verificationId 반환. */
    private long create(String token, String realName) throws Exception {
        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(realName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.otpExpiresAt").exists())
                // 카운트다운 단일 출처 — 서버 계산 잔여 초(양수). 시계/TZ 무관. (stub TTL 180s)
                .andExpect(jsonPath("$.otpExpiresInSeconds").value(greaterThan(0)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("verificationId").asLong();
    }

    /** 생성 + 매직 OTP 확인 → VERIFIED. verificationId 반환. */
    private long verify(String token, String realName) throws Exception {
        long id = create(token, realName);
        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody(StubIdentityVerifier.MAGIC_OTP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
        return id;
    }

    @Test
    @DisplayName("S1: 생성 요청 시 201 + status=READY + otpExpiresAt (OTP 발송 대기, 아직 미인증)")
    void create_returnsReady() throws Exception {
        Account student = createStudent("s1@test.com", "diverS1");
        long id = create(tokenFor(student), "한어진");

        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IdentityVerificationStatus.READY);
        assertThat(saved.getPortoneVerificationId()).startsWith("iv_");
        assertThat(saved.getCarrier()).isEqualTo(Carrier.SKT);
        assertThat(saved.getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("S2: 매직 OTP 로 confirm 하면 200 VERIFIED + DB 에 CI/DI(암호화 저장→복호화 조회) 채워진다")
    void confirm_verifiesAndFillsCiDi() throws Exception {
        Account student = createStudent("s2@test.com", "diverS2");
        long id = verify(tokenFor(student), "한어진");

        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);
        assertThat(saved.getCi()).startsWith("CI-STUB-"); // 컨버터가 복호화해 원문 반환
        assertThat(saved.getDi()).startsWith("DI-STUB-");
        assertThat(saved.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("S3: VERIFIED 후 GET /me 는 verified=true + verificationId + realName (재사용/ skip)")
    void verifyThenGetMe() throws Exception {
        Account student = createStudent("s3@test.com", "diverS3");
        String token = tokenFor(student);
        long id = verify(token, "한어진");

        mockMvc.perform(get("/identity-verifications/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.verificationId").value((int) id))
                .andExpect(jsonPath("$.realName").value("한어진"))
                .andExpect(jsonPath("$.verifiedAt").exists());
    }

    @Test
    @DisplayName("S4: 하이픈 표기 휴대폰 번호는 숫자만 남게 정규화되어 저장된다 (다날에도 canonical 형태로 나감)")
    void create_normalizesPhoneNumber() throws Exception {
        Account student = createStudent("s4@test.com", "diverS4");
        String token = tokenFor(student);

        MvcResult result = mockMvc.perform(post("/identity-verifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("한어진", "010-1234-5678", "1998-09-14")))
                .andExpect(status().isCreated())
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("verificationId").asLong();
        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getPhoneNumber()).isEqualTo("01012345678"); // 구분자 제거
        assertThat(saved.getBirth()).isEqualTo("19980914");
    }

    @Test
    @DisplayName("V1: 틀린 OTP 로 confirm 하면 200 FAILED + errorCode=OTP_MISMATCH, 레코드는 READY 유지(재입력 허용)")
    void confirm_wrongOtp() throws Exception {
        Account student = createStudent("v1@test.com", "diverV1");
        String token = tokenFor(student);
        long id = create(token, "한어진");

        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("999999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("OTP_MISMATCH"));

        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IdentityVerificationStatus.READY); // 세션 유지
        assertThat(saved.getAttemptCount()).isEqualTo(1);

        // 미인증이므로 GET /me 는 여전히 false
        mockMvc.perform(get("/identity-verifications/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    @DisplayName("V2: 휴대폰 접두사가 01 로 시작하지 않으면 400 + 필드 메시지 — 레코드 미생성(다날 미호출)")
    void create_invalidPhonePrefix() throws Exception {
        Account student = createStudent("v2@test.com", "diverV2");
        // 제보된 케이스 — FE 검증을 우회해 콘솔/직접 호출로 들어오는 번호. msg 로 어느 필드인지 알려준다.
        expectRejectedBeforeSending(tokenFor(student),
                createBody("한어진", "9191234567", "19980914"), "휴대폰 번호 형식이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("V3: 013/014/015 번호대(IoT·부가서비스)는 SMS 수신이 불가하므로 400 — 레코드 미생성")
    void create_nonSmsCapableNumberBand() throws Exception {
        Account student = createStudent("v3@test.com", "diverV3");
        expectRejectedBeforeSending(tokenFor(student),
                createBody("한어진", "01312345678", "19980914"), "휴대폰 번호 형식이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("V4: 생년월일이 yyyyMMdd 가 아니면(13월) 400 + 필드 메시지 — 레코드 미생성")
    void create_invalidBirth() throws Exception {
        Account student = createStudent("v4@test.com", "diverV4");
        expectRejectedBeforeSending(tokenFor(student),
                createBody("한어진", "01012345678", "19981345"), "생년월일 형식이 올바르지 않습니다. (yyyyMMdd)");
    }

    @Test
    @DisplayName("V5: OTP 가 6자리 숫자가 아니면 400 + 필드 메시지 — 시도 횟수(attemptCount)를 소모하지 않는다")
    void confirm_malformedOtpDoesNotBurnAttempt() throws Exception {
        Account student = createStudent("v5@test.com", "diverV5");
        String token = tokenFor(student);
        long id = create(token, "한어진");

        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody("12345"))) // 5자리 — 구조적으로 정답일 수 없음
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("인증번호는 6자리 숫자입니다."));

        IdentityVerification saved = identityVerificationRepo.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IdentityVerificationStatus.READY);
        assertThat(saved.getAttemptCount()).isZero(); // 진짜 추측만 센다
    }

    @Test
    @DisplayName("D1: resend 하면 200 READY + 새 otpExpiresAt·otpExpiresInSeconds, 시도 카운트 초기화")
    void resend() throws Exception {
        Account student = createStudent("d1@test.com", "diverD1");
        String token = tokenFor(student);
        long id = create(token, "한어진");
        // 한 번 틀려 시도수 1 만든 뒤 재발송으로 초기화
        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(otpBody("111111")));

        mockMvc.perform(post("/identity-verifications/" + id + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.otpExpiresAt").exists())
                .andExpect(jsonPath("$.otpExpiresInSeconds").value(greaterThan(0)));

        assertThat(identityVerificationRepo.findById(id).orElseThrow().getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("T1: 발송만 하고(READY) 확인 전이면 GET /me 는 200 {verified:false} (VERIFIED 만 재사용)")
    void getMe_whenOnlyReady() throws Exception {
        Account student = createStudent("t1@test.com", "diverT1");
        String token = tokenFor(student);
        create(token, "한어진"); // READY only

        mockMvc.perform(get("/identity-verifications/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.verificationId").doesNotExist());
    }

    @Test
    @DisplayName("T2: 본인확인 이력이 없는 사용자의 GET /me 는 200 {verified:false} (404 아님)")
    void getMe_whenNeverVerified() throws Exception {
        Account student = createStudent("t2@test.com", "diverT2");
        mockMvc.perform(get("/identity-verifications/me").header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    @DisplayName("R1: 남의 verificationId 로 confirm 하면 400 (비소유 = 존재 숨김)")
    void confirm_othersId_rejected() throws Exception {
        Account owner = createStudent("r1a@test.com", "diverR1a");
        Account other = createStudent("r1b@test.com", "diverR1b");
        long id = create(tokenFor(owner), "한어진");

        mockMvc.perform(post("/identity-verifications/" + id + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otpBody(StubIdentityVerifier.MAGIC_OTP)))
                .andExpect(status().isBadRequest());

        assertThat(identityVerificationRepo.findById(id).orElseThrow().getStatus())
                .isEqualTo(IdentityVerificationStatus.READY); // 손대지 못함
    }
}
