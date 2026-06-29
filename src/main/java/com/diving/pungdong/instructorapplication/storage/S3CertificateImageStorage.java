package com.diving.pungdong.instructorapplication.storage;

import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * 운영용 — {@link S3Uploader} 에 위임해 자격증 이미지를 <b>비공개</b>로 올린다(public ACL 없음).
 * 저장 참조 = S3 객체 key. 열람은 어드민/본인 조회 시점에만 presigned GET URL 로 발급한다
 * (TTL 짧게 — 자격증은 개인정보라 공개 URL 로 노출하지 않음).
 * {@code pungdong.storage.s3.enabled=true} 일 때만 활성.
 */
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3CertificateImageStorage implements CertificateImageStorage {

    private static final String CERTIFICATE_DIR = "instructorCertificate";
    /** 열람 URL 수명 — 심사 시 한 번 보는 용도라 짧게. 유출돼도 이 창 안에서만 유효. */
    private static final Duration VIEW_TTL = Duration.ofMinutes(3);

    private final S3Uploader s3Uploader;

    @Override
    public String store(MultipartFile image, Long ownerId) throws IOException {
        return s3Uploader.uploadPrivate(image, CERTIFICATE_DIR, ownerId);
    }

    @Override
    public String viewUrl(String key) {
        return s3Uploader.generatePresignedGetUrl(key, VIEW_TTL);
    }
}
