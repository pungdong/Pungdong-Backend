package com.diving.pungdong.instructorapplication.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 자격증 이미지 저장 경계. {@link com.diving.pungdong.notification.fcm.FcmGateway} 와 동일한
 * "interface + 환경별 구현 교체" 패턴.
 *
 * <ul>
 *   <li>운영: {@link S3CertificateImageStorage} ({@code pungdong.storage.s3.enabled=true})</li>
 *   <li>로컬/dev: {@link LocalCertificateImageStorage} (기본값 — S3 미연동 구간을 메움)</li>
 * </ul>
 *
 * {@code @ConditionalOnProperty} 로 정확히 하나만 활성 — 컴포넌트 스캔 순서에 의존하지 않는다.
 */
public interface CertificateImageStorage {

    /** 이미지를 저장하고 접근 가능한 URL 을 반환한다. */
    String store(MultipartFile image, String ownerEmail) throws IOException;
}
