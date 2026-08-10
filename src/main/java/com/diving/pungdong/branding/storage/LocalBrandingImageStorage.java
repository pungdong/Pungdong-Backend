package com.diving.pungdong.branding.storage;

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
 * dev/로컬용 — S3 없이도 업로드가 동작하도록 디스크에 저장하고 {@code /local-uploads/**} URL 을 반환한다.
 * 정적 서빙은 instructor-application 의 {@code LocalUploadsWebConfig} 가 base dir 전체를 이미 핸들링한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalBrandingImageStorage implements BrandingImageStorage {

    static final String SUB_DIR = "branding";
    static final String URL_PREFIX = "/local-uploads";

    private final Path baseDir;
    private final String baseUrl;

    public LocalBrandingImageStorage(
            @Value("${pungdong.storage.local.dir:local-uploads}") String dir,
            @Value("${pungdong.storage.local.base-url:http://localhost:8080}") String baseUrl) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String store(MultipartFile image) throws IOException {
        Path targetDir = baseDir.resolve(SUB_DIR);
        Files.createDirectories(targetDir);

        String fileName = UUID.randomUUID() + extension(image);
        Path target = targetDir.resolve(fileName);
        image.transferTo(target.toFile());

        String url = baseUrl + URL_PREFIX + "/" + SUB_DIR + "/" + fileName;
        log.info("[storage-local] saved branding image to {} → {}", target, url);
        return url;
    }

    private String extension(MultipartFile image) {
        String ext = StringUtils.getFilenameExtension(image.getOriginalFilename());
        return ext != null ? "." + ext.toLowerCase() : ".png";
    }
}
