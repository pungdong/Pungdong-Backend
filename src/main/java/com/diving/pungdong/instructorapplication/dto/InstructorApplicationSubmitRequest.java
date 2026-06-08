package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 강사 신청 제출(2-phase 2단계). 본인확인 id + 단체 + (이미 업로드된) 자격증 이미지 URL 목록.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationSubmitRequest {

    @NotNull
    private Long verificationId;

    @NotEmpty
    private String organizationCode;

    /** organizationCode 가 "OTHER" 일 때 필수 — 검증은 서비스에서 (조건부라 @AssertTrue 대신). */
    private String organizationOther;

    @NotEmpty
    private List<String> certificateImageUrls;
}
