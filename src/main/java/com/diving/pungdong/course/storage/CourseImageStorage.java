package com.diving.pungdong.course.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 코스(강의) 이미지 저장 경계 — 강의상세 상단 캐로셀에 노출될 사진.
 * {@link com.diving.pungdong.instructorapplication.storage.CertificateImageStorage} 와 동일한
 * "interface + 환경별 구현 교체" 패턴.
 *
 * <ul>
 *   <li>운영: {@link S3CourseImageStorage} ({@code pungdong.storage.s3.enabled=true})</li>
 *   <li>로컬/dev: {@link LocalCourseImageStorage} (기본값 — S3 미연동 구간을 메움)</li>
 * </ul>
 *
 * <p>이번 단계는 <b>사진만</b> 다룬다. 영상은 모델(`MediaKind.VIDEO`)에 자리만 두고 업로드/트랜스코딩은 후속.
 * {@code @ConditionalOnProperty} 로 정확히 하나만 활성 — 컴포넌트 스캔 순서에 의존하지 않는다.
 */
public interface CourseImageStorage {

    /** 이미지를 저장하고 접근 가능한 URL 을 반환한다. */
    String store(MultipartFile image, String ownerEmail) throws IOException;
}
