package com.diving.pungdong.course.storage;

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
 * 아래 {@code course/} 에 저장하고, {@code /local-uploads/**} 절대 URL 을 반환한다. → FE 가 AWS 없이도
 * 업로드한 이미지를 실제로 확인 가능.
 *
 * <p>정적 서빙은 instructor-application 도메인의 {@code LocalUploadsWebConfig} 가 이미 base dir 전체를
 * {@code /local-uploads/**} 로 핸들링한다(공유 인프라) — 같은 base dir 아래 {@code course/} 하위 폴더라
 * 별도 핸들러가 필요 없다. 두 저장소가 같은 {@code pungdong.storage.s3.enabled} 게이트를 공유.
 *
 * <p>{@code pungdong.storage.s3.enabled} 가 false/미설정일 때 활성 (dev 기본값).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalCourseImageStorage implements CourseImageStorage {

    static final String SUB_DIR = "course";
    /** 정적 서빙 prefix — instructor-application 의 {@code LocalUploadsWebConfig} 핸들러 경로와 일치. */
    static final String URL_PREFIX = "/local-uploads";

    private final Path baseDir;
    private final String baseUrl;

    public LocalCourseImageStorage(
            @Value("${pungdong.storage.local.dir:local-uploads}") String dir,
            @Value("${pungdong.storage.local.base-url:http://localhost:8080}") String baseUrl) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String store(MultipartFile image, String ownerEmail) throws IOException {
        Path targetDir = baseDir.resolve(SUB_DIR);
        Files.createDirectories(targetDir);

        String fileName = UUID.randomUUID() + extension(image);
        Path target = targetDir.resolve(fileName);
        image.transferTo(target.toFile());

        String url = baseUrl + URL_PREFIX + "/" + SUB_DIR + "/" + fileName;
        log.info("[storage-local] saved course image to {} → {}", target, url);
        return url;
    }

    private String extension(MultipartFile image) {
        String original = image.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(original);
        return ext != null ? "." + ext.toLowerCase() : ".png";
    }
}
