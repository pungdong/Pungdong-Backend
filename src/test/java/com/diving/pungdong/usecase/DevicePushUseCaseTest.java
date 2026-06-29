package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.account.FirebaseToken;
import com.diving.pungdong.account.FirebaseTokenJpaRepo;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 디바이스(FCM 토큰) 등록/해제(/me/devices) 실행 스펙.
 *
 * <p>@DisplayName 을 위에서 아래로 읽으면 계약이 드러난다: 세션 신분으로 토큰을 upsert 하고(D*),
 * 해제하며(D3), 인증/검증을 강제한다(R*, V*). 실 스택(H2 + 실 시큐리티 체인 + 실 FirebaseTokenService),
 * 최종 상태는 FirebaseTokenJpaRepo 로 검증. 정책·계약 = docs/features/push.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevicePushUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired FirebaseTokenJpaRepo firebaseTokenJpaRepo;

    @AfterEach
    void cleanUp() {
        firebaseTokenJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
    }

    private Account createStudent(String email, String nick) {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        Account account = Account.builder()
                .email(email).password(passwordEncoder.encode("1234")).nickName(nick)
                .phoneNumber("01012345678").birth("1990-01-01").gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).profilePhoto(photo)
                .build();
        return accountJpaRepo.save(account);
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    @Test
    @DisplayName("D1: 토큰+platform 등록 → 200, 내 계정에 토큰 저장(deviceType 캡처)")
    void register_persistsTokenWithPlatform() throws Exception {
        Account me = createStudent("d1@test.com", "d1user");

        mockMvc.perform(post("/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-d1\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk());

        FirebaseToken saved = firebaseTokenJpaRepo.findByToken("fcm-d1").orElseThrow();
        assertThat(saved.getAccount().getId()).isEqualTo(me.getId());
        assertThat(saved.getDeviceType()).isEqualTo(DeviceType.IOS);
    }

    @Test
    @DisplayName("D2: 같은 토큰 재등록 → upsert(행 1개 유지, 중복 생성 안 함)")
    void register_isUpsert() throws Exception {
        Account me = createStudent("d2@test.com", "d2user");
        String auth = tokenFor(me);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/me/devices")
                            .header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"fcm-d2\",\"platform\":\"ANDROID\"}"))
                    .andExpect(status().isOk());
        }

        assertThat(firebaseTokenJpaRepo.findByAccount_Id(me.getId())).hasSize(1);
    }

    @Test
    @DisplayName("D3: 등록한 토큰 해제(DELETE) → 204, 행 삭제")
    void unregister_removesToken() throws Exception {
        Account me = createStudent("d3@test.com", "d3user");
        String auth = tokenFor(me);
        mockMvc.perform(post("/me/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"fcm-d3\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/me/devices/{token}", "fcm-d3")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        assertThat(firebaseTokenJpaRepo.findByToken("fcm-d3")).isEmpty();
    }

    @Test
    @DisplayName("D4: platform 없이 등록 → 200, deviceType=null (platform 은 선택)")
    void register_platformIsOptional() throws Exception {
        Account me = createStudent("d4@test.com", "d4user");

        mockMvc.perform(post("/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-d4\"}"))
                .andExpect(status().isOk());

        assertThat(firebaseTokenJpaRepo.findByToken("fcm-d4").orElseThrow().getDeviceType()).isNull();
    }

    @Test
    @DisplayName("R1: 인증 없이 등록 → 401 (세션 신분 필수)")
    void register_requiresAuth() throws Exception {
        mockMvc.perform(post("/me/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-r1\",\"platform\":\"IOS\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(firebaseTokenJpaRepo.findByToken("fcm-r1")).isEmpty();
    }

    @Test
    @DisplayName("V1: token 빈값 → 400")
    void register_rejectsEmptyToken() throws Exception {
        Account me = createStudent("v1@test.com", "v1user");

        mockMvc.perform(post("/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"platform\":\"IOS\"}"))
                .andExpect(status().isBadRequest());
    }
}
