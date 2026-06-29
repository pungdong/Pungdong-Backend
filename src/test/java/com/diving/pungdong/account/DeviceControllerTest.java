package com.diving.pungdong.account;

import com.diving.pungdong.global.config.RestDocsConfiguration;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /me/devices 컨트롤러 와이어링 + REST Docs 스니펫(device-register / device-unregister) 생성.
 * 동작(상태) 스펙은 usecase/DevicePushUseCaseTest. 정책 = docs/features/push.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import(RestDocsConfiguration.class)
class DeviceControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountJpaRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoJpaRepo;
    @Autowired FirebaseTokenJpaRepo firebaseTokenJpaRepo;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        firebaseTokenJpaRepo.deleteAll();
        accountJpaRepo.deleteAll();
        profilePhotoJpaRepo.deleteAll();
    }

    private Account createStudent() {
        ProfilePhoto photo = profilePhotoJpaRepo.save(
                ProfilePhoto.builder().imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL).build());
        return accountJpaRepo.save(Account.builder()
                .email("device@test.com").password(passwordEncoder.encode("1234")).nickName("deviceuser")
                .phoneNumber("01012345678").birth("1990-01-01").gender(Gender.MALE)
                .roles(new HashSet<>(Set.of(Role.STUDENT))).profilePhoto(photo)
                .build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    @Test
    @DisplayName("디바이스 토큰 등록")
    void registerDevice() throws Exception {
        Account me = createStudent();

        mockMvc.perform(post("/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-value\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk())
                .andDo(document("device-register",
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("access token")),
                        requestFields(
                                fieldWithPath("token").description("FCM 디바이스 토큰"),
                                fieldWithPath("platform").optional().description("IOS | ANDROID (선택)"))));
    }

    @Test
    @DisplayName("디바이스 토큰 해제")
    void unregisterDevice() throws Exception {
        Account me = createStudent();
        String auth = tokenFor(me);
        mockMvc.perform(post("/me/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-value\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/me/devices/{token}", "fcm-token-value")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent())
                .andDo(document("device-unregister",
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("access token")),
                        pathParameters(
                                parameterWithName("token").description("해제할 FCM 디바이스 토큰"))));
    }
}
