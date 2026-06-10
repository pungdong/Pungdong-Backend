package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

/**
 * 자격증 1건 = 발급 단체 + 이미지. 제출 요청(certificates 리스트)과 조회 응답에 공용으로 쓰인다.
 * 한 종목 신청에 여러 단체 자격증을 담는다. (향후 레벨/등급 필드 추가 자리)
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ApplicationCertificateDto {
    /** 발급 단체 code (Sanity 카탈로그, 예 "AIDA"/"PADI"/"OTHER"). */
    private String organizationCode;
    /** organizationCode 가 "OTHER" 일 때 직접입력. */
    private String organizationOther;
    /** 2-phase 업로드로 받은 이미지 URL. */
    private String fileURL;
}
