package com.diving.pungdong.course.storage;

import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 운영용 — {@link S3Uploader} 에 위임해 코스 이미지를 <b>공개</b>로 올린다(공개 버킷 + CloudFront OAC).
 * 코스 이미지는 노출/SEO 가 목적이라 안정 공개 URL 을 반환한다(자격증의 비공개 presigned 와 반대).
 * {@code pungdong.storage.s3.enabled=true} 일 때만 활성.
 */
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3CourseImageStorage implements CourseImageStorage {

    private static final String COURSE_DIR = "course";

    private final S3Uploader s3Uploader;

    @Override
    public String store(MultipartFile image, String ownerEmail) throws IOException {
        return s3Uploader.uploadPublic(image, COURSE_DIR);
    }
}
