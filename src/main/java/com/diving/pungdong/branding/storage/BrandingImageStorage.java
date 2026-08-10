package com.diving.pungdong.branding.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 브랜딩 게시물 사진 저장 게이트. 코스 이미지와 같은 구조 — 운영은 공개 버킷(CDN), 로컬은 디스크 stub.
 *
 * <p>{@code S3Uploader} 를 직접 주입하지 않고 인터페이스를 두는 이유: 프로필·리뷰 이미지가 그렇게 해서
 * <b>로컬 폴백이 없다</b>(로컬에서도 S3 를 호출한다). 같은 함정을 새로 만들지 않는다.
 */
public interface BrandingImageStorage {

    /** 저장하고 표시 가능한 URL 을 반환한다. */
    String store(MultipartFile image) throws IOException;
}
