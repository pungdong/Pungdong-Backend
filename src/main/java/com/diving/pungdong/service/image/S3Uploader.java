package com.diving.pungdong.service.image;


import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * S3 업로드 게이트. 두 가지 규칙을 지킨다:
 *
 * <ol>
 *   <li><b>임시 파일을 만들지 않는다</b> — {@link MultipartFile} 를 스트림으로 바로 올린다.
 *       (예전엔 작업 디렉터리에 temp 파일을 썼는데, 컨테이너는 비루트 유저 + 읽기전용 작업
 *       디렉터리라 그 쓰기가 실패했다.)</li>
 *   <li><b>public ACL 을 붙이지 않는다</b> — 업로드 버킷은 Block Public Access 가 켜져 있어
 *       canned ACL(public-read) 을 단 PutObject 는 거부된다. 객체는 비공개로 올라가고,
 *       비공개 객체는 {@link #generatePresignedGetUrl} 로 한시 열람한다.</li>
 * </ol>
 *
 * 키에는 PII 를 넣지 않는다(이메일 등). 파일명은 UUID, 비공개 객체는 소유자 id 로 그룹핑한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class S3Uploader {

    private final AmazonS3Client amazonS3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    /**
     * 공개-의도 이미지 전용 공개 버킷(CloudFront OAC origin). 비면 메인(비공개) 버킷으로 폴백 —
     * 인프라(공개 버킷)가 아직 없는 환경에서도 업로드 자체는 깨지지 않게.
     */
    @Value("${cloud.aws.s3.public-bucket:}")
    private String publicBucket;

    /**
     * 공개 객체의 표시 base URL — 보통 CDN 도메인(예 {@code https://cdn.plop.cool}). 비면 객체의
     * S3 URL 로 폴백. day-1 커스텀 도메인 고정이라 저장값을 완성된 공개 URL 로 둔다.
     */
    @Value("${pungdong.storage.public-base-url:}")
    private String publicBaseUrl;

    /**
     * 공개-의도 이미지(코스/프로필/리뷰 등) 업로드 — 기존 호출처 호환용. 객체를 올리고 객체
     * URL 을 반환한다. (버킷이 비공개라 이 URL 의 직접 열람은 공개-버킷 전환 전까지 동작하지
     * 않는다.) {@code userEmail} 은 레거시 시그니처 호환으로 남아있을 뿐 키에 쓰지 않는다(PII 키 금지).
     */
    public String upload(MultipartFile multipartFile, String dirName, String userEmail) throws IOException {
        String key = putObject(bucket, multipartFile, dirName + "/" + uniqueName(multipartFile));
        return amazonS3Client.getUrl(bucket, key).toString();
    }

    /**
     * 공개 이미지(코스/프로필/리뷰) 업로드 — 공개 버킷(CloudFront OAC)에 올리고 <b>안정 공개 URL</b>
     * ({@code {publicBaseUrl}/{key}})을 반환한다. 버킷/ base URL 이 설정 안 된 환경에선 메인 버킷 +
     * S3 객체 URL 로 폴백(현행 동작 유지) — 공개 버킷 인프라가 붙기 전까지 안전.
     */
    public String uploadPublic(MultipartFile multipartFile, String dirName) throws IOException {
        String targetBucket = StringUtils.hasText(publicBucket) ? publicBucket : bucket;
        String key = putObject(targetBucket, multipartFile, dirName + "/" + uniqueName(multipartFile));
        return StringUtils.hasText(publicBaseUrl)
                ? publicBaseUrl + "/" + key
                : amazonS3Client.getUrl(targetBucket, key).toString();
    }

    /**
     * 비공개 이미지(자격증/보험 등) 업로드 — public ACL 없이 올리고 <b>객체 key</b> 를 반환한다.
     * 키 = {@code dirName/{ownerId}/{uuid}.{ext}} : 회원별 그룹핑(탈퇴 시 prefix 일괄 삭제)
     * + PII 비포함. 열람은 {@link #generatePresignedGetUrl} 로 한시 발급.
     */
    public String uploadPrivate(MultipartFile multipartFile, String dirName, Long ownerId) throws IOException {
        return putObject(bucket, multipartFile, dirName + "/" + ownerId + "/" + uniqueName(multipartFile));
    }

    /** 비공개 객체를 {@code ttl} 동안만 열람 가능한 presigned GET URL 로 발급(로컬 서명, 네트워크 호출 없음). */
    public String generatePresignedGetUrl(String key, Duration ttl) {
        Date expiration = new Date(System.currentTimeMillis() + ttl.toMillis());
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET)
                .withExpiration(expiration);
        return amazonS3Client.generatePresignedUrl(request).toString();
    }

    /**
     * {@link MultipartFile} 을 임시 파일 없이 스트림으로 직접 PutObject. contentLength 를 명시해
     * SDK 가 전체 버퍼링하지 않게 한다. public ACL 미부여(버킷 BPA 와 호환). 반환 = 객체 key.
     */
    private String putObject(String targetBucket, MultipartFile file, String key) throws IOException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (StringUtils.hasText(file.getContentType())) {
            metadata.setContentType(file.getContentType());
        }
        try (InputStream in = file.getInputStream()) {
            amazonS3Client.putObject(new PutObjectRequest(targetBucket, key, in, metadata));
        }
        return key;
    }

    private String uniqueName(MultipartFile file) {
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        return UUID.randomUUID() + (ext != null ? "." + ext.toLowerCase() : ".png");
    }

    /** 저장된 값(호출처가 넘긴 key/URL)으로 객체 삭제. 호출처가 저장한 식별자 의미를 따른다. */
    public void deleteFileFromS3(String fileURL) {
        amazonS3Client.deleteObject(new DeleteObjectRequest(bucket, fileURL));
    }
}
