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
    @DisplayName("업로드 파일이 local-uploads/instructorCertificate/{ownerId} 아래 저장되고 서빙 URL 을 반환한다")
    void store_writesFileToDisk_andReturnsServingUrl(@TempDir Path tempDir) throws Exception {
        LocalCertificateImageStorage storage =
                new LocalCertificateImageStorage(tempDir.toString(), "http://localhost:8080/");

        MockMultipartFile image = new MockMultipartFile(
                "image", "padi_owd.JPG", "image/jpeg", "fake-image-bytes".getBytes());

        String url = storage.store(image, 1L);

        // URL 형태: <base>/local-uploads/instructorCertificate/{ownerId}/<uuid>.jpg (base 끝 슬래시는 정규화)
        assertThat(url).startsWith("http://localhost:8080/local-uploads/instructorCertificate/1/");
        assertThat(url).endsWith(".jpg"); // 원본 확장자 보존 (소문자)
        // 로컬 저장 참조는 그대로 서빙 가능 — viewUrl 은 변환 없이 동일 URL 을 돌려준다.
        assertThat(storage.viewUrl(url)).isEqualTo(url);

        // 실제 파일이 디스크에 존재하고 내용이 보존됐는지
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path saved = tempDir.resolve("instructorCertificate").resolve("1").resolve(fileName);
        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.readAllBytes(saved)).isEqualTo("fake-image-bytes".getBytes());
    }

    @Test
    @DisplayName("저장 참조는 소유자 판정이 가능하다 — 남의 id 로는 매칭되지 않고, 7 과 71 도 안 섞인다")
    void storedRef_isAttributableToOwner(@TempDir Path tempDir) throws Exception {
        LocalCertificateImageStorage storage =
                new LocalCertificateImageStorage(tempDir.toString(), "http://localhost:8080");

        String url = storage.store(new MockMultipartFile("image", "c.png", "image/png", "x".getBytes()), 7L);

        assertThat(CertificateImageStorage.isOwnedBy(url, 7L)).isTrue();
        assertThat(CertificateImageStorage.isOwnedBy(url, 8L)).isFalse();
        // 끝 슬래시가 없으면 71 의 경로가 7 로 오인된다 — 경계값을 박아둔다.
        assertThat(CertificateImageStorage.isOwnedBy(url, 71L)).isFalse();
        String other = storage.store(new MockMultipartFile("image", "c.png", "image/png", "x".getBytes()), 71L);
        assertThat(CertificateImageStorage.isOwnedBy(other, 7L)).isFalse();
        assertThat(CertificateImageStorage.isOwnedBy(other, 71L)).isTrue();
    }

    @Test
    @DisplayName("deleteAllFor 는 그 회원 폴더만 통째로 지운다 (탈퇴 PII 파기) — 없으면 no-op")
    void deleteAllFor_removesOnlyThatOwnersFiles(@TempDir Path tempDir) throws Exception {
        LocalCertificateImageStorage storage =
                new LocalCertificateImageStorage(tempDir.toString(), "http://localhost:8080");
        storage.store(new MockMultipartFile("image", "a.png", "image/png", "a".getBytes()), 7L);
        storage.store(new MockMultipartFile("image", "b.png", "image/png", "b".getBytes()), 7L);
        String keep = storage.store(new MockMultipartFile("image", "c.png", "image/png", "c".getBytes()), 8L);

        storage.deleteAllFor(7L);

        assertThat(Files.exists(tempDir.resolve("instructorCertificate").resolve("7"))).isFalse();
        String keepName = keep.substring(keep.lastIndexOf('/') + 1);
        assertThat(Files.exists(tempDir.resolve("instructorCertificate").resolve("8").resolve(keepName))).isTrue();

        storage.deleteAllFor(7L); // 멱등 — 두 번 돌려도 예외 없음
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
