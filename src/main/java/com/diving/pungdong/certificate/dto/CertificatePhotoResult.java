package com.diving.pungdong.certificate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 사진 업로드(2-phase 1단계) 응답 — {@code POST /certificates/photos}.
 *
 * <p>필드명이 강사 자격증 업로드({@code POST /instructor-applications/certificate-images})와 <b>같다</b>
 * — FE 가 업로드 코드를 재사용할 수 있게 의도적으로 맞췄다.
 *
 * <p>URL 이 아니라 <b>key</b> 인 이유: 자격증 사진은 PII 라 비공개 버킷에 올라가고 <b>영구 공개 URL 이
 * 존재하지 않는다.</b> 등록 전 미리보기는 방금 고른 로컬 파일로, 등록 후 표시는 조회 응답의
 * {@code photoViewUrl}(presigned)로 한다.
 */
@Getter
@Builder
@AllArgsConstructor
public class CertificatePhotoResult {
    private final String fileKey;
}
