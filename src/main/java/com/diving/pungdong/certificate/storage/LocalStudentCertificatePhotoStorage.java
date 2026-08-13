package com.diving.pungdong.certificate.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * dev/로컬용 — S3 미연동 구간을 메운다. {@code /local-uploads/**} 정적 서빙
 * ({@code LocalUploadsWebConfig})으로 접근 가능한 절대 URL 을 반환한다.
 *
 * <p>경로에 <b>{@code {ownerId}/} 세그먼트를 넣는다</b> — S3 구현과 같은 모양이라
 * {@link StudentCertificatePhotoStorage#isOwnedBy} 소유 검증과 {@link #deleteAllFor} 를
 * dev/테스트에서 그대로 검증할 수 있다. (강사 자격증 쪽 로컬 구현이 예전에 평탄 저장이라
 * 이 두 경로를 prod 에서만 밟을 수 있었고, 그래서 같은 PR 에서 함께 정렬했다.)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalStudentCertificatePhotoStorage implements StudentCertificatePhotoStorage {

    /** 정적 서빙 prefix — {@code LocalUploadsWebConfig} 의 핸들러 경로와 일치해야 한다. */
    static final String URL_PREFIX = "/local-uploads";

    private final Path baseDir;
    private final String baseUrl;

    public LocalStudentCertificatePhotoStorage(
            @Value("${pungdong.storage.local.dir:local-uploads}") String dir,
            @Value("${pungdong.storage.local.base-url:http://localhost:8080}") String baseUrl) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String store(MultipartFile image, Long ownerId) throws IOException {
        Path targetDir = baseDir.resolve(PHOTO_DIR).resolve(String.valueOf(ownerId));
        Files.createDirectories(targetDir);

        String fileName = UUID.randomUUID() + extension(image);
        Path target = targetDir.resolve(fileName);
        image.transferTo(target.toFile());

        String url = baseUrl + URL_PREFIX + "/" + StudentCertificatePhotoStorage.ownerPrefix(ownerId) + fileName;
        log.info("[storage-local] saved student certificate photo to {} → {}", target, url);
        return url;
    }

    /** 로컬은 정적 서빙 URL 이 곧 저장 참조라 변환 없이 반환. */
    @Override
    public String viewUrl(String storedRef) {
        return storedRef;
    }

    @Override
    public void delete(String storedRef) {
        Path file = resolve(storedRef);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 소유자 폴더를 통째로. 없으면 no-op(멱등). */
    @Override
    public void deleteAllFor(Long ownerId) {
        Path ownerDir = baseDir.resolve(PHOTO_DIR).resolve(String.valueOf(ownerId));
        if (!Files.isDirectory(ownerDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(ownerDir)) {
            for (Path file : files.collect(Collectors.toList())) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(ownerDir);
            log.info("[storage-local] deleted student certificate photos for owner {}", ownerId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 서빙 URL → 디스크 경로. {@code /local-uploads/studentCertificate/{ownerId}/{file}} 뒤쪽만 쓴다.
     * 저장 참조가 이 형태가 아니면 지울 대상을 특정할 수 없으므로 no-op.
     */
    private Path resolve(String storedRef) {
        if (!StringUtils.hasText(storedRef)) {
            return null;
        }
        int at = storedRef.indexOf(URL_PREFIX + "/" + PHOTO_DIR + "/");
        if (at < 0) {
            return null;
        }
        String relative = storedRef.substring(at + URL_PREFIX.length() + 1);
        return baseDir.resolve(relative).normalize();
    }

    private String extension(MultipartFile image) {
        String ext = StringUtils.getFilenameExtension(image.getOriginalFilename());
        return ext != null ? "." + ext.toLowerCase() : ".png";
    }
}
