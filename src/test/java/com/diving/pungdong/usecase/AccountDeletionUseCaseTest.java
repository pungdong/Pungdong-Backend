package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountAnonymizationService;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.FirebaseTokenJpaRepo;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.service.LectureService;
import com.diving.pungdong.service.image.S3Uploader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원탈퇴(soft delete) → 유예 → PII 익명화 파이프라인의 실행 스펙.
 *
 * <p>@DisplayName 을 위에서 아래로 읽으면 정책이 그대로 드러난다:
 * 탈퇴는 즉시 접근을 끊고(W*), 유예기간이 지나야 식별정보를 파기하며(A*), 유예 안에선 복구된다(R*).
 * 실 스택(H2 + 실 시큐리티 체인 + 실 AccountService/익명화 서비스), 외부 경계(LectureService·S3)만 모킹.
 * 정책·보존 항목·법적 근거 = docs/features/account-deletion.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired PasswordEncoder passwordEncoder;

    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired FirebaseTokenJpaRepo firebaseTokenJpaRepo;
    @Autowired AccountAnonymizationService anonymizationService;

    // 진짜 외부 경계만 모킹 — 탈퇴 시 강의 일괄 close, 익명화 시 S3 삭제.
    @MockBean LectureService lectureService;
    @MockBean S3Uploader s3Uploader;

    @AfterEach
    void cleanUp() {
        firebaseTokenJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
        redisTemplate.execute((RedisConnection conn) -> {
            conn.flushDb();
            return null;
        });
    }

    private Account createStudent(String email, String nick) {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        Account account = Account.builder()
                .email(email)
                .password(passwordEncoder.encode("1234"))
                .nickName(nick)
                .phoneNumber("01012345678")
                .birth("1990-01-01")
                .gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(Role.STUDENT)))
                .profilePhoto(photo)
                .build();
        return accountJpaRepo.save(account);
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String deleteBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("password", "1234"));
    }

    @Test
    @DisplayName("W1: 탈퇴 요청 → isDeleted=true·deletedAt 기록, 이후 이메일/비번 로그인 차단")
    void withdraw_marksDeleted_andBlocksLogin() throws Exception {
        Account student = createStudent("w1@test.com", "w1user");
        String token = tokenFor(student);

        mockMvc.perform(delete("/account")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody()))
                .andExpect(status().isNoContent());

        Account reloaded = accountJpaRepo.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getIsDeleted()).isTrue();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getAnonymizedAt()).isNull();

        // 탈퇴 계정은 로그인 불가
        mockMvc.perform(post("/sign/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("email", "w1@test.com", "password", "1234"))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("W2: 탈퇴 직후 기존 access token 으로 보호된 API 호출 → 401 (블랙리스트로 즉시 무효)")
    void withdraw_blacklistsCurrentAccessToken() throws Exception {
        Account student = createStudent("w2@test.com", "w2user");
        String token = tokenFor(student);

        mockMvc.perform(delete("/account")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/sign/firebase-token")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T1: 탈퇴 계정의 refresh token 으로 /sign/refresh → 거부 (재발급 우회 차단)")
    void withdraw_blocksRefresh() throws Exception {
        Account student = createStudent("t1@test.com", "t1user");
        String accessToken = tokenFor(student);
        String refreshToken = jwtTokenProvider.createRefreshToken(String.valueOf(student.getId()));

        mockMvc.perform(delete("/account")
                .header(HttpHeaders.AUTHORIZATION, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/sign/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("R1: 유예기간 내에는 이메일 인증으로 복구 → isDeleted=false 로 되돌아감")
    void restore_withinGrace_reactivates() throws Exception {
        Account student = createStudent("r1@test.com", "r1user");
        String token = tokenFor(student);

        mockMvc.perform(delete("/account")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteBody()))
                .andExpect(status().isNoContent());

        // 이메일 인증코드 시드(EmailService.verifyAuthCode 가 읽는 키)
        redisTemplate.opsForValue().set("r1@test.com" + "EmailAuth", "111222");

        mockMvc.perform(patch("/account/deleted-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("email", "r1@test.com", "emailAuthCode", "111222"))))
                .andExpect(status().isNoContent());

        Account reloaded = accountJpaRepo.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getIsDeleted()).isFalse();
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("A1: 유예기간 경과 후 익명화 → 이메일/닉네임/전화/생일 PII 파기, anonymizedAt 기록")
    void anonymize_afterGrace_scrubsPii() {
        Account student = createStudent("a1@test.com", "a1user");
        student.setIsDeleted(true);
        student.setDeletedAt(LocalDateTime.now());
        accountJpaRepo.save(student);

        // now 를 유예기간 너머로 밀어 대상에 포함시킨다(스케줄러가 now 를 주입하는 것과 동형)
        List<Long> due = anonymizationService.findDueAccountIds(LocalDateTime.now().plusDays(40));
        assertThat(due).contains(student.getId());

        anonymizationService.anonymize(student.getId());

        Account reloaded = accountJpaRepo.findById(student.getId()).orElseThrow();
        assertThat(reloaded.getAnonymizedAt()).isNotNull();
        assertThat(reloaded.getEmail()).isEqualTo("deleted_" + student.getId() + "@deleted.local");
        assertThat(reloaded.getNickName()).isNull();
        assertThat(reloaded.getPhoneNumber()).isNull();
        assertThat(reloaded.getBirth()).isNull();
        assertThat(reloaded.getGender()).isNull();
        // row 자체는 보존(결제·계약 FK 무결성)
        assertThat(reloaded.getIsDeleted()).isTrue();
    }

    @Test
    @DisplayName("A2: 익명화는 멱등 — 이미 익명화된 계정을 다시 돌려도 변화 없음")
    void anonymize_isIdempotent() {
        Account student = createStudent("a2@test.com", "a2user");
        student.setIsDeleted(true);
        student.setDeletedAt(LocalDateTime.now().minusDays(40));
        accountJpaRepo.save(student);

        anonymizationService.anonymize(student.getId());
        Account first = accountJpaRepo.findById(student.getId()).orElseThrow();
        LocalDateTime firstAt = first.getAnonymizedAt();
        String anonEmail = first.getEmail();

        anonymizationService.anonymize(student.getId());
        Account second = accountJpaRepo.findById(student.getId()).orElseThrow();

        assertThat(second.getAnonymizedAt()).isEqualTo(firstAt);
        assertThat(second.getEmail()).isEqualTo(anonEmail);
    }

    @Test
    @DisplayName("A3: 유예기간이 안 지난 탈퇴 계정은 익명화 대상에 포함되지 않는다")
    void anonymize_respectsGraceWindow() {
        Account student = createStudent("a3@test.com", "a3user");
        student.setIsDeleted(true);
        student.setDeletedAt(LocalDateTime.now());
        accountJpaRepo.save(student);

        List<Long> dueNow = anonymizationService.findDueAccountIds(LocalDateTime.now());
        assertThat(dueNow).doesNotContain(student.getId());
    }

    @Test
    @DisplayName("R2: 익명화된 계정은 원래 이메일로 복구를 시도해도 실패한다 (식별정보 파기 완료)")
    void restore_afterAnonymization_fails() throws Exception {
        Account student = createStudent("r2@test.com", "r2user");
        student.setIsDeleted(true);
        student.setDeletedAt(LocalDateTime.now().minusDays(40));
        accountJpaRepo.save(student);

        anonymizationService.anonymize(student.getId());

        redisTemplate.opsForValue().set("r2@test.com" + "EmailAuth", "111222");

        mockMvc.perform(patch("/account/deleted-state")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("email", "r2@test.com", "emailAuthCode", "111222"))))
                .andExpect(status().is4xxClientError());
    }
}
