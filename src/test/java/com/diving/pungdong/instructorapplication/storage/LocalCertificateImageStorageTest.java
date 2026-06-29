package com.diving.pungdong.instructorapplication.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev 로컬 이미지 저장 어댑터의 실제 디스크 쓰기 동작 검증 (Spring 없이 직접).
 * use-case 테스트는 이 경계를 mock 하므로, 실제 저장 경로는 여기서 단독으로 확인한다.
 */
class LocalCertificateImageStorageTest {

    @Test
    @DisplayName("업로드 파일이 local-uploads/instructorCertificate 아래 실제로 저장되고 서빙 URL 을 반환한다")
    void store_writesFileToDisk_andReturnsServingUrl(@TempDir Path tempDir) throws Exception {
        LocalCertificateImageStorage storage =
                new LocalCertificateImageStorage(tempDir.toString(), "http://localhost:8080/");

        MockMultipartFile image = new MockMultipartFile(
                "image", "padi_owd.JPG", "image/jpeg", "fake-image-bytes".getBytes());

        String url = storage.store(image, 1L);

        // URL 형태: <base>/local-uploads/instructorCertificate/<uuid>.jpg (base 의 끝 슬래시는 정규화)
        assertThat(url).startsWith("http://localhost:8080/local-uploads/instructorCertificate/");
        assertThat(url).endsWith(".jpg"); // 원본 확장자 보존 (소문자)
        // 로컬 저장 참조는 그대로 서빙 가능 — viewUrl 은 변환 없이 동일 URL 을 돌려준다.
        assertThat(storage.viewUrl(url)).isEqualTo(url);

        // 실제 파일이 디스크에 존재하고 내용이 보존됐는지
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path saved = tempDir.resolve("instructorCertificate").resolve(fileName);
        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.readAllBytes(saved)).isEqualTo("fake-image-bytes".getBytes());
    }

    @Test
    @DisplayName("확장자 없는 파일은 .png 로 떨어진다")
    void store_defaultsToPng_whenNoExtension(@TempDir Path tempDir) throws Exception {
        LocalCertificateImageStorage storage =
                new LocalCertificateImageStorage(tempDir.toString(), "http://localhost:8080");

        MockMultipartFile image = new MockMultipartFile(
                "image", "noext", "image/png", "x".getBytes());

        String url = storage.store(image, 1L);

        assertThat(url).endsWith(".png");
    }
}
