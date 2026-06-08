package com.diving.pungdong.instructorapplication.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * dev 로컬 저장소({@link LocalCertificateImageStorage})가 저장한 파일을 {@code /local-uploads/**}
 * 로 정적 서빙한다. S3 활성(prod)일 땐 등록되지 않는다.
 *
 * <p>보안: {@code /local-uploads/**} 는 {@code SecurityConfiguration.webSecurityCustomizer} 에서
 * 정적 리소스로 화이트리스트 (인증 불필요).
 */
@Configuration
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "false", matchIfMissing = true)
public class LocalUploadsWebConfig implements WebMvcConfigurer {

    private final String dir;

    public LocalUploadsWebConfig(@Value("${pungdong.storage.local.dir:local-uploads}") String dir) {
        this.dir = dir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path base = Paths.get(dir).toAbsolutePath().normalize();
        // 시작 시 폴더를 미리 만든다 — 없으면 toUri() 가 끝 슬래시를 안 붙여 base 가 디렉토리로
        // 인식되지 않고 정적 서빙이 404 가 된다.
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String location = base.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/"; // ResourceLocation 은 디렉토리 base 임을 끝 슬래시로 표시해야 함
        }
        registry.addResourceHandler(LocalCertificateImageStorage.URL_PREFIX + "/**")
                .addResourceLocations(location);
    }
}
