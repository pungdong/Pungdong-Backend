package com.diving.pungdong.global.storage;


import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

    /**
     * 공개 이미지 삭제 — 저장값을 <b>(버킷, key)</b> 로 환원해 지운다. 저장값 포맷이 시대별로 세 가지라
     * 그대로 key 로 쓸 수 없다:
     *
     * <ol>
     *   <li><b>완성 CDN URL</b> {@code https://cdn.plop.cool/profile-photo/{uuid}.png} — 현재 형식
     *       ({@link #uploadPublic} 이 {@code publicBaseUrl} 을 붙여 저장)</li>
     *   <li><b>S3 객체 URL</b> {@code https://{bucket}.s3.{region}.amazonaws.com/{key}} —
     *       {@code publicBaseUrl} 미설정 환경의 폴백</li>
     *   <li><b>맨 파일명</b> — 레거시. 저장값이 곧 key</li>
     * </ol>
     *
     * <p>예전 구현은 어떤 값이든 <b>비공개 버킷의 key</b> 로 취급해서, 공개 버킷 전환(#140) 이후로는
     * 실제로 아무것도 지우지 못했다. S3 {@code deleteObject} 는 없는 key 에도 204 를 주므로 조용히
     * 성공한 것처럼 보였고, 그래서 탈퇴 익명화가 얼굴 사진(PII)을 그대로 남겼다.
     *
     * <p><b>공유 기본 이미지({@code ProfilePhoto.DEFAULT_IMAGE_URL})는 호출처가 걸러야 한다</b> —
     * 여기서는 값의 의미를 모른다.
     */
    public void deletePublicObject(String storedValue) {
        if (!StringUtils.hasText(storedValue)) {
            return;
        }
        String fallbackBucket = StringUtils.hasText(publicBucket) ? publicBucket : bucket;

        if (StringUtils.hasText(publicBaseUrl) && storedValue.startsWith(publicBaseUrl + "/")) {
            deleteObject(fallbackBucket, storedValue.substring(publicBaseUrl.length() + 1));
            return;
        }
        if (storedValue.startsWith("http://") || storedValue.startsWith("https://")) {
            deleteByUrl(storedValue, fallbackBucket);
            return;
        }
        deleteObject(fallbackBucket, storedValue); // 레거시 — 저장값이 곧 key
    }

    private void deleteByUrl(String url, String fallbackBucket) {
        String host;
        String key;
        try {
            URI uri = URI.create(url);
            host = uri.getHost() == null ? "" : uri.getHost();
            key = uri.getPath() == null ? "" : uri.getPath();
        } catch (IllegalArgumentException e) {
            deleteObject(fallbackBucket, url); // 파싱 불가 — 통째로 key 취급
            return;
        }
        if (key.startsWith("/")) {
            key = key.substring(1);
        }

        // virtual-hosted style: {bucket}.s3.{region}.amazonaws.com/{key}
        int s3At = host.indexOf(".s3.");
        if (s3At > 0) {
            deleteObject(host.substring(0, s3At), key);
            return;
        }
        // path style: s3.{region}.amazonaws.com/{bucket}/{key}
        if (host.startsWith("s3.") || host.startsWith("s3-")) {
            int slash = key.indexOf('/');
            if (slash > 0) {
                deleteObject(key.substring(0, slash), key.substring(slash + 1));
                return;
            }
        }
        deleteObject(fallbackBucket, key); // 커스텀 CDN 도메인
    }

    /**
     * 비공개 객체 삭제 — 저장값이 <b>곧 key</b> 인 경우({@link #uploadPrivate} 가 반환한 값)에만 쓴다.
     *
     * <p><b>{@link #deletePublicObject} 를 대신 쓰면 안 된다</b>: 그쪽은 저장값을 <i>공개</i> 버킷 기준으로
     * (버킷, key) 환원하는 함수라, 비공개 key 를 넘기면 엉뚱한 버킷을 지우려 든다. S3 {@code deleteObject} 는
     * 없는 key 에도 204 를 주므로 <b>조용히 성공한 것처럼 보인다</b> — #140 이후 프로필 사진이 실제로
     * 안 지워졌던 사고가 정확히 이 모양이었다.
     */
    public void deletePrivateObject(String key) {
        deleteObject(bucket, key);
    }

    /**
     * 비공개 객체를 <b>prefix 단위로</b> 일괄 삭제 — 탈퇴 PII 파기용
     * (예: {@code instructorCertificate/{accountId}/}).
     *
     * <p>1000건 단위 페이지네이션을 끝까지 따라간다. 호출처가 best-effort 로 감싸는 것을 전제로,
     * 여기서는 예외를 삼키지 않는다(부분 삭제 후 실패하면 그대로 전파 — 재시도 시 남은 것부터 지운다).
     */
    public void deletePrivateObjectsUnderPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return;
        }
        ListObjectsV2Request request = new ListObjectsV2Request().withBucketName(bucket).withPrefix(prefix);
        int deleted = 0;
        ListObjectsV2Result page;
        do {
            page = amazonS3Client.listObjectsV2(request);
            for (S3ObjectSummary summary : page.getObjectSummaries()) {
                amazonS3Client.deleteObject(new DeleteObjectRequest(bucket, summary.getKey()));
                deleted++;
            }
            request.setContinuationToken(page.getNextContinuationToken());
        } while (page.isTruncated());

        log.info("[s3] 비공개 prefix 삭제 bucket={} prefix={} count={}", bucket, prefix, deleted);
    }

    private void deleteObject(String targetBucket, String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        amazonS3Client.deleteObject(new DeleteObjectRequest(targetBucket, key));
        log.info("[s3] 객체 삭제 bucket={} key={}", targetBucket, key);
    }
}
