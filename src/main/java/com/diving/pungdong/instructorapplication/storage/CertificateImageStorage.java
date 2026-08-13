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

    /** 저장 루트 디렉터리 — S3 key prefix / 로컬 하위 폴더 공통. 소유자 그룹핑의 기준점. */
    String CERTIFICATE_DIR = "instructorCertificate";

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

    /**
     * 한 회원의 자격증 이미지를 <b>전부</b> 삭제한다 — 탈퇴 PII 파기용.
     *
     * <p>{@link #store} 가 소유자별로 그룹핑해 저장하는 이유가 바로 이것이다
     * (docs/features/image-storage-and-serving.md §2 "회원별 그룹핑 → 탈퇴 PII 익명화 시 prefix 일괄 삭제").
     * 설계 의도만 문서에 있고 구현이 없어서 탈퇴한 회원의 자격증 이미지가 그대로 남아 있었다.
     */
    void deleteAllFor(Long ownerId);

    /** 소유자별 그룹 경로 조각 — {@code instructorCertificate/{ownerId}/}. */
    static String ownerPrefix(Long ownerId) {
        return CERTIFICATE_DIR + "/" + ownerId + "/";
    }

    /**
     * 이 저장 참조가 {@code ownerId} 의 것인가 — 제출 JSON 이 <b>남의 fileKey</b> 를 참조하지 못하게 막는 검사.
     *
     * <p><b>왜 필요한가</b>: presigned URL 은 경로에 객체 key 를 그대로 담는다. 그 URL 이 한 번 새면
     * (스크린샷·CS 티켓·로그) 누구든 key 를 뽑아 <b>자기 신청에 붙여 짧은 TTL 을 무한 재발급</b>할 수 있다 —
     * TTL 로 좁혀둔 열람 창이 영구 접근이 된다. key 가 UUID 라 추측은 불가능하므로 <i>유출된 경우에 한한</i>
     * 방어지만, 비용이 문자열 비교 한 번이다.
     *
     * <p>S3(=key `instructorCertificate/7/x.png`)와 로컬(=URL `http://…/local-uploads/instructorCertificate/7/x.png`)
     * 의 저장 참조 모양이 다르므로 <b>포함</b> 검사를 쓴다. 끝 슬래시가 있어 {@code 7} 과 {@code 71} 은 안 섞인다.
     */
    static boolean isOwnedBy(String storedRef, Long ownerId) {
        return storedRef != null && ownerId != null && storedRef.contains(ownerPrefix(ownerId));
    }
}
