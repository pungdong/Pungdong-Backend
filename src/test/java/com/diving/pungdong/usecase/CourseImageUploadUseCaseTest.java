package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 이미지 업로드(2-phase 1단계) use-case — <b>실제 로컬 stub</b>({@link
 * com.diving.pungdong.course.storage.LocalCourseImageStorage}) 을 그대로 태운다(S3 미접속, mock 아님).
 * test 프로파일은 {@code pungdong.storage.s3.enabled} 미설정 → local stub 활성.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. S* 성공 / V* 검증 / R* 권한.
 * 업로드가 실제로 디스크에 파일을 쓰고 접근 URL 을 돌려주는지(= FE 가 AWS 없이 확인 가능)까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CourseImageUploadUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;

    @Value("${pungdong.storage.local.dir:local-uploads}")
    String localDir;

    /** 이 테스트가 디스크에 만든 파일만 추적해 삭제 — dev 의 실제 업로드물은 건드리지 않는다. */
    private final Set<Path> created = new HashSet<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (Path p : created) {
            Files.deleteIfExists(p);
        }
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private Account createAccount(String email, String nick, Role role) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String tokenFor(Account account) {
        return jwtTokenProvider.createAccessToken(String.valueOf(account.getId()), account.getRoles());
    }

    /** 반환 URL(.../local-uploads/course/<file>) → 디스크 경로로 변환. */
    private Path diskPathOf(String fileURL) {
        String fileName = fileURL.substring(fileURL.lastIndexOf('/') + 1);
        return Paths.get(localDir).toAbsolutePath().normalize().resolve("course").resolve(fileName);
    }

    /* ════════════════ S — 성공 ════════════════ */

    @Test
    @DisplayName("S1: 사진을 업로드하면 접근 URL 을 돌려주고 실제로 디스크에 파일을 쓴다 (로컬 stub)")
    void uploadCourseImage_returnsUrl_andWritesFile() throws Exception {
        Account student = createAccount("c1@test.com", "diverC1", Role.STUDENT);
        MockMultipartFile file = new MockMultipartFile(
                "image", "pool.png", MediaType.IMAGE_PNG_VALUE, "fake-bytes".getBytes());

        MvcResult result = mockMvc.perform(multipart("/course-images")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileURL").exists())
                .andReturn();

        String fileURL = objectMapperRead(result);
        assertThat(fileURL).startsWith("http://localhost:8080/local-uploads/course/").endsWith(".png");

        Path onDisk = diskPathOf(fileURL);
        created.add(onDisk);
        assertThat(Files.exists(onDisk)).isTrue();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo("fake-bytes".getBytes());
    }

    /* ════════════════ V — 검증 ════════════════ */

    @Test
    @DisplayName("V1: 빈 파일을 올리면 400 (저장하지 않는다)")
    void emptyFile_returns400() throws Exception {
        Account student = createAccount("c2@test.com", "diverC2", Role.STUDENT);
        MockMultipartFile empty = new MockMultipartFile(
                "image", "empty.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);

        mockMvc.perform(multipart("/course-images")
                        .file(empty)
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isBadRequest());
    }

    /* ════════════════ R — 권한 ════════════════ */

    @Test
    @DisplayName("R1: 인증 없이 업로드하면 401")
    void unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image", "pool.png", MediaType.IMAGE_PNG_VALUE, "fake-bytes".getBytes());

        mockMvc.perform(multipart("/course-images").file(file))
                .andExpect(status().isUnauthorized());
    }

    private String objectMapperRead(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.fileURL");
    }
}
