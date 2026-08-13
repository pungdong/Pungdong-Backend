package com.diving.pungdong.instructorapplication.storage;

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
 * dev/로컬용 — S3 미연동 구간을 메운다. 업로드 파일을 로컬 디스크({@code pungdong.storage.local.dir})
 * 아래 {@code instructorCertificate/} 에 저장하고, {@code /local-uploads/**} 정적 서빙
 * ({@link LocalUploadsWebConfig})으로 접근 가능한 절대 URL 을 반환한다. → FE 가 AWS 없이도
 * 업로드한 이미지를 실제로 확인 가능.
 *
 * <p>{@code pungdong.storage.s3.enabled} 가 false/미설정일 때 활성 (dev 기본값).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalCertificateImageStorage implements CertificateImageStorage {

    /** 정적 서빙 prefix — {@link LocalUploadsWebConfig} 의 핸들러 경로와 일치해야 한다. */
    static final String URL_PREFIX = "/local-uploads";

    private final Path baseDir;
    private final String baseUrl;

    public LocalCertificateImageStorage(
            @Value("${pungdong.storage.local.dir:local-uploads}") String dir,
            @Value("${pungdong.storage.local.base-url:http://localhost:8080}") String baseUrl) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * ⚠️ 경로에 <b>{@code {ownerId}/} 세그먼트가 들어간다</b> — S3 구현({@code uploadPrivate})과 같은 모양.
     * 예전엔 로컬만 평탄하게(`instructorCertificate/{uuid}.png`) 저장해서 소유자 정보가 경로에 없었고,
     * 그 때문에 {@link CertificateImageStorage#isOwnedBy} 소유 검증과 {@link #deleteAllFor} 를
     * <b>dev/테스트에서 검증할 수 없었다</b>(prod 에서만 동작하는 코드 = 안 돌려본 코드).
     */
    @Override
    public String store(MultipartFile image, Long ownerId) throws IOException {
        Path targetDir = baseDir.resolve(CERTIFICATE_DIR).resolve(String.valueOf(ownerId));
        Files.createDirectories(targetDir);

        String fileName = UUID.randomUUID() + extension(image);
        Path target = targetDir.resolve(fileName);
        image.transferTo(target.toFile());

        String url = baseUrl + URL_PREFIX + "/" + CertificateImageStorage.ownerPrefix(ownerId) + fileName;
        log.info("[storage-local] saved certificate image to {} → {}", target, url);
        return url;
    }

    /** 로컬은 정적 서빙 URL 을 저장 참조로 쓰므로 그대로 열람 가능 — 변환 없이 반환. */
    @Override
    public String viewUrl(String storedRef) {
        return storedRef;
    }

    /** 소유자 폴더를 통째로 지운다. 없으면 no-op(멱등). */
    @Override
    public void deleteAllFor(Long ownerId) {
        Path ownerDir = baseDir.resolve(CERTIFICATE_DIR).resolve(String.valueOf(ownerId));
        if (!Files.isDirectory(ownerDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(ownerDir)) {
            for (Path file : files.collect(Collectors.toList())) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(ownerDir);
            log.info("[storage-local] deleted certificate images for owner {}", ownerId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String extension(MultipartFile image) {
        String original = image.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(original);
        return ext != null ? "." + ext.toLowerCase() : ".png";
    }
}
