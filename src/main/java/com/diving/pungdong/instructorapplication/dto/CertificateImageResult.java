package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

/**
 * 2-phase 업로드 1단계 결과 — 저장된 자격증 이미지의 <b>참조 key</b>.
 * 비공개 객체라 직접 열람 URL 이 아니다. 제출 JSON 의 {@code certificates[].fileKey} 로 이 값을
 * 그대로 돌려보내고, 표시용 URL 은 조회 응답의 {@code viewUrl}(presigned)로 받는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CertificateImageResult {
    private String fileKey;
}
