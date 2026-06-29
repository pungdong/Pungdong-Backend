package com.diving.pungdong.instructorapplication.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

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

    static final String SUB_DIR = "instructorCertificate";
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

    @Override
    public String store(MultipartFile image, Long ownerId) throws IOException {
        Path targetDir = baseDir.resolve(SUB_DIR);
        Files.createDirectories(targetDir);

        String fileName = UUID.randomUUID() + extension(image);
        Path target = targetDir.resolve(fileName);
        image.transferTo(target.toFile());

        String url = baseUrl + URL_PREFIX + "/" + SUB_DIR + "/" + fileName;
        log.info("[storage-local] saved certificate image to {} → {}", target, url);
        return url;
    }

    /** 로컬은 정적 서빙 URL 을 저장 참조로 쓰므로 그대로 열람 가능 — 변환 없이 반환. */
    @Override
    public String viewUrl(String storedRef) {
        return storedRef;
    }

    private String extension(MultipartFile image) {
        String original = image.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(original);
        return ext != null ? "." + ext.toLowerCase() : ".png";
    }
}
