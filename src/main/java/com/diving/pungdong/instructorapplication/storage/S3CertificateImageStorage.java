package com.diving.pungdong.instructorapplication.storage;

import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 운영용 — 기존 {@link S3Uploader} 에 위임해 AWS S3 에 올린다.
 * {@code pungdong.storage.s3.enabled=true} 일 때만 활성 (Phase 4 버킷 provision 후).
 */
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3CertificateImageStorage implements CertificateImageStorage {

    private static final String CERTIFICATE_DIR = "instructorCertificate";

    private final S3Uploader s3Uploader;

    @Override
    public String store(MultipartFile image, String ownerEmail) throws IOException {
        return s3Uploader.upload(image, CERTIFICATE_DIR, ownerEmail);
    }
}
