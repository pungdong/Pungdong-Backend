package com.diving.pungdong.global.storage;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 공개 이미지 삭제 — 저장값을 (버킷, key) 로 환원하는 규칙의 사양.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. D* 삭제 대상 해석 / V* 안전장치.
 *
 * <p>왜 이 테스트가 있나: 예전 구현은 저장값이 무엇이든 <b>비공개 버킷의 key</b> 로 취급해서, 공개
 * 버킷 전환(#140) 이후로는 실제로 아무것도 지우지 못했다. S3 는 없는 key 에도 204 를 주므로 조용히
 * 성공한 것처럼 보였고 — 탈퇴 익명화가 얼굴 사진(PII)을 남겼다. 저장값 포맷이 시대별로 3종이라
 * 회귀가 눈에 안 띄므로 각 포맷을 못 박아둔다.
 */
class S3UploaderDeleteTest {

    private static final String PRIVATE_BUCKET = "plop-prod-uploads";
    private static final String PUBLIC_BUCKET = "plop-prod-public";
    private static final String CDN = "https://cdn.plop.cool";

    private AmazonS3Client amazonS3Client;
    private S3Uploader s3Uploader;

    @BeforeEach
    void setUp() {
        amazonS3Client = mock(AmazonS3Client.class);
        s3Uploader = new S3Uploader(amazonS3Client);
        ReflectionTestUtils.setField(s3Uploader, "bucket", PRIVATE_BUCKET);
        ReflectionTestUtils.setField(s3Uploader, "publicBucket", PUBLIC_BUCKET);
        ReflectionTestUtils.setField(s3Uploader, "publicBaseUrl", CDN);
    }

    private DeleteObjectRequest captureDelete() {
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(amazonS3Client).deleteObject(captor.capture());
        return captor.getValue();
    }

    /* ════════════════ D — 삭제 대상 해석 ════════════════ */

    @Test
    @DisplayName("D1: 완성 CDN URL 이면 공개 버킷의 key 로 환원해 지운다 (현재 저장 형식)")
    void cdnUrl_resolvesToPublicBucketKey() {
        s3Uploader.deletePublicObject(CDN + "/profile-photo/abc-123.png");

        DeleteObjectRequest request = captureDelete();
        assertThat(request.getBucketName()).isEqualTo(PUBLIC_BUCKET);
        assertThat(request.getKey()).isEqualTo("profile-photo/abc-123.png");
    }

    @Test
    @DisplayName("D2: S3 객체 URL 이면 호스트에서 버킷을 뽑아 지운다 (publicBaseUrl 미설정 시절 업로드분)")
    void s3ObjectUrl_resolvesBucketFromHost() {
        s3Uploader.deletePublicObject(
                "https://" + PUBLIC_BUCKET + ".s3.ap-northeast-2.amazonaws.com/profile-photo/old.png");

        DeleteObjectRequest request = captureDelete();
        assertThat(request.getBucketName()).isEqualTo(PUBLIC_BUCKET);
        assertThat(request.getKey()).isEqualTo("profile-photo/old.png");
    }

    @Test
    @DisplayName("D3: 맨 파일명(레거시)이면 저장값을 그대로 key 로 써서 공개 버킷에서 지운다")
    void legacyBareFileName_isUsedAsKey() {
        s3Uploader.deletePublicObject("legacy-name2021-06-07T18:08:34.png");

        DeleteObjectRequest request = captureDelete();
        assertThat(request.getBucketName()).isEqualTo(PUBLIC_BUCKET);
        assertThat(request.getKey()).isEqualTo("legacy-name2021-06-07T18:08:34.png");
    }

    @Test
    @DisplayName("D4: 공개 버킷이 설정되지 않은 환경이면 메인 버킷으로 폴백한다 (업로드 폴백과 대칭)")
    void publicBucketUnset_fallsBackToMainBucket() {
        ReflectionTestUtils.setField(s3Uploader, "publicBucket", "");
        ReflectionTestUtils.setField(s3Uploader, "publicBaseUrl", "");

        s3Uploader.deletePublicObject("profile-photo/abc.png");

        DeleteObjectRequest request = captureDelete();
        assertThat(request.getBucketName()).isEqualTo(PRIVATE_BUCKET);
        assertThat(request.getKey()).isEqualTo("profile-photo/abc.png");
    }

    /* ════════════════ V — 안전장치 ════════════════ */

    @Test
    @DisplayName("V1: 저장값이 비어 있으면 S3 를 호출하지 않는다")
    void blankValue_doesNotCallS3() {
        s3Uploader.deletePublicObject(null);
        s3Uploader.deletePublicObject("");
        s3Uploader.deletePublicObject("   ");

        verify(amazonS3Client, never()).deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("V2: CDN 루트만 있고 key 가 없으면 S3 를 호출하지 않는다 (버킷 전체를 겨냥하지 않는다)")
    void urlWithoutKey_doesNotCallS3() {
        s3Uploader.deletePublicObject(CDN + "/");

        verify(amazonS3Client, never()).deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class));
    }
}
