package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 강사 신청 제출(2-phase 2단계). 종목 + 본인확인 id + (조건부) 자격증 목록.
 *
 * <p>자격증은 <b>여러 단체</b>를 담을 수 있다(AIDA+PADI+...). 각 항목 = (단체, 이미지). 자격증 필요
 * 여부는 종목의 {@code requiresCertification} — 필요 종목은 1건 이상 필수, 불필요(수영/서핑)는 생략 가능.
 * 그래서 bean-validation 으로 강제하지 않고 서비스에서 조건부 검증한다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationSubmitRequest {

    /** 신청 종목 코드 (GET /disciplines 의 code). */
    @NotEmpty
    private String disciplineCode;

    @NotNull
    private Long verificationId;

    /** 자격증 목록 (단체+이미지). 자격증 필요 종목에선 1건 이상, 불필요 종목에선 생략 가능. */
    private List<ApplicationCertificateDto> certificates;
}
