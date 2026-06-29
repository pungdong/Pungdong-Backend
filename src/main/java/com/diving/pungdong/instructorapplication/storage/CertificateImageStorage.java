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

    /**
     * 이미지를 비공개로 저장하고 <b>저장 참조</b>를 반환한다.
     * (S3 = 객체 key, 로컬 = 서빙 URL). 소유자 id 는 회원별 그룹핑/정리에 쓴다.
     */
    String store(MultipartFile image, Long ownerId) throws IOException;

    /**
     * 저장 참조를 한시 열람 가능한 URL 로 변환한다 — S3 는 presigned GET(짧은 TTL),
     * 로컬은 정적 서빙 URL 을 그대로 반환. 자격증 이미지는 개인정보라 공개 URL 로 두지 않고,
     * 어드민/본인이 조회하는 시점에만 이 URL 을 발급한다.
     */
    String viewUrl(String storedRef);
}
