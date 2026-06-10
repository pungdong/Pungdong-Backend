package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import javax.validation.constraints.NotEmpty;

/**
 * 승인된 강사가 자격증 관리 탭에서 자격증 1건을 추가하는 요청.
 *
 * <p>MVP: 검수 없이 바로 append (상태 APPROVED 유지). 향후 "인증 요청하기" → 검수 → 자격 승격/
 * 멀티자격으로 확장 시 이 요청이 검수 트리거가 된다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AddCertificateRequest {

    /** 자격증을 추가할 종목 코드 (이미 승인된 강사 신청이 있어야 함). */
    @NotEmpty
    private String disciplineCode;

    @NotEmpty
    private String organizationCode;

    private String organizationOther;

    /** 2-phase 업로드로 받은 이미지 URL. */
    @NotEmpty
    private String fileURL;
}
