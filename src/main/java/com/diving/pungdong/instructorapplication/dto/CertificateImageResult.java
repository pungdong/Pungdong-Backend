package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

/** 2-phase 업로드 1단계 결과 — 업로드된 자격증 이미지의 S3 URL. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CertificateImageResult {
    private String fileURL;
}
