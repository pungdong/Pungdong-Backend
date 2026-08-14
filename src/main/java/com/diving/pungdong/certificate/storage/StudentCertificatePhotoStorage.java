package com.diving.pungdong.certificate.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 학생 자격증 사진 저장 경계 — {@code instructorapplication/storage/CertificateImageStorage} 와 같은
 * "interface + 환경별 구현 교체" 패턴({@code pungdong.storage.s3.enabled} 게이트).
 *
 * <p><b>접근 등급 = 비공개(PII).</b> 실물 카드 촬영본이라 이름·자격증번호가 찍힌다.
 * {@code docs/features/image-storage-and-serving.md} §1 이 "비공개(PII) = 자격증·보험"으로 등급을
 * 못박아 뒀고, 학생 자격증은 <b>대상이 강사보다 훨씬 많다.</b> 공개 CDN 경로를 쓰지 않는다 —
 * 비공개 버킷에 저장하고 조회 시점에만 짧은 TTL presigned 를 발급한다.
 *
 * <p>왜 강사 쪽 인터페이스를 재사용하지 않나: prefix 가 다르고({@code studentCertificate/} vs
 * {@code instructorCertificate/}) 삭제 수명주기도 다르다(이쪽은 사용자가 개별 삭제한다).
 * 한 인터페이스에 prefix 를 파라미터로 넣으면 <b>호출처가 남의 prefix 를 지정할 수 있게</b> 되므로
 * 경계를 나눠 각자 자기 prefix 만 만지게 한다.
 */
public interface StudentCertificatePhotoStorage {

    /** 저장 루트 — S3 key prefix / 로컬 하위 폴더 공통. 소유자 그룹핑의 기준점. */
    String PHOTO_DIR = "studentCertificate";

    /** 이미지를 비공개로 저장하고 저장 참조를 반환한다(S3 = 객체 key, 로컬 = 서빙 URL). */
    String store(MultipartFile image, Long ownerId) throws IOException;

    /** 저장 참조 → 한시 열람 URL(S3 = presigned GET, 로컬 = 그대로). */
    String viewUrl(String storedRef);

    /** 자격증 1건 삭제 시 그 사진도 제거. 실패는 호출처가 삼킨다(고아 1개 < 삭제 실패). */
    void delete(String storedRef);

    /** 탈퇴 PII 파기 — 그 회원의 사진 전부. */
    void deleteAllFor(Long ownerId);

    /** 소유자별 그룹 경로 조각 — {@code studentCertificate/{ownerId}/}. */
    static String ownerPrefix(Long ownerId) {
        return PHOTO_DIR + "/" + ownerId + "/";
    }

    /**
     * 이 저장 참조가 {@code ownerId} 의 것인가 — 등록 JSON 이 <b>남의 photoFileKey</b> 를 참조하지
     * 못하게 막는 검사.
     *
     * <p>presigned URL 은 경로에 key 를 그대로 담는다. 그 URL 이 한 번 새면 누구든 key 를 뽑아
     * 자기 자격증에 붙여 <b>짧은 TTL 을 무한 재발급</b>할 수 있다 — 열람 창이 영구 접근이 된다.
     * S3(key)와 로컬(URL)의 저장 참조 모양이 달라 <b>포함</b> 검사를 쓴다. 끝 슬래시가 있어
     * {@code 7} 과 {@code 71} 은 안 섞인다.
     */
    static boolean isOwnedBy(String storedRef, Long ownerId) {
        if (storedRef == null || ownerId == null) {
            return false;
        }
        // `..` 을 먼저 잘라낸다 — 이 검사가 유일한 보안 경계라고 문서가 약속하므로, prefix 만 맞으면
        // `studentCertificate/{내 id}/../../etc/x` 가 통과해 로컬 구현의 경로 이탈로 이어진다.
        if (storedRef.contains("..")) {
            return false;
        }
        return storedRef.contains(ownerPrefix(ownerId));
    }
}
