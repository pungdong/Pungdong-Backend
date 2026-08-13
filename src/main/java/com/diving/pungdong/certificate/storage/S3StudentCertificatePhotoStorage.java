package com.diving.pungdong.certificate.storage;

import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * 운영용 — 비공개 버킷에 저장하고 조회 시점에만 presigned GET 을 발급한다.
 * {@code pungdong.storage.s3.enabled=true} 일 때만 활성.
 */
@Component
@ConditionalOnProperty(name = "pungdong.storage.s3.enabled", havingValue = "true")
@RequiredArgsConstructor
public class S3StudentCertificatePhotoStorage implements StudentCertificatePhotoStorage {

    /**
     * 열람 URL 수명. 강사 자격증(심사 1회 열람)과 같은 3분.
     *
     * <p>⚠️ 이 값이 FE 화면 흐름을 제약한다 — 목록을 열어두고 3분 넘겨 상세로 들어가면 403 이다.
     * 그래서 단건 조회({@code GET /certificates/{id}})가 presigned 를 재발급하고, FE 는 이미지
     * 로드 실패 시 1회 재조회한다(계약 Q3).
     */
    private static final Duration VIEW_TTL = Duration.ofMinutes(3);

    private final S3Uploader s3Uploader;

    @Override
    public String store(MultipartFile image, Long ownerId) throws IOException {
        return s3Uploader.uploadPrivate(image, PHOTO_DIR, ownerId);
    }

    @Override
    public String viewUrl(String key) {
        return s3Uploader.generatePresignedGetUrl(key, VIEW_TTL);
    }

    /** ⚠️ {@code deletePublicObject} 를 쓰면 안 된다 — 공개 버킷 기준 환원이라 조용히 엉뚱한 걸 지운다. */
    @Override
    public void delete(String key) {
        s3Uploader.deletePrivateObject(key);
    }

    @Override
    public void deleteAllFor(Long ownerId) {
        s3Uploader.deletePrivateObjectsUnderPrefix(StudentCertificatePhotoStorage.ownerPrefix(ownerId));
    }
}
