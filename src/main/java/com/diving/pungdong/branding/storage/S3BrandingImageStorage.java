package com.diving.pungdong.branding.storage;

import com.diving.pungdong.global.storage.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 운영용 — 공개 버킷(CloudFront OAC)에 올리고 <b>안정 공개 URL</b>을 반환한다. 브랜딩 사진은 노출이
 * 목적이라 자격증의 비공개 presigned 와 반대 등급이다.
 */
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3BrandingImageStorage implements BrandingImageStorage {

    static final String BRANDING_DIR = "branding";

    private final S3Uploader s3Uploader;

    @Override
    public String store(MultipartFile image) throws IOException {
        return s3Uploader.uploadPublic(image, BRANDING_DIR);
    }
}
