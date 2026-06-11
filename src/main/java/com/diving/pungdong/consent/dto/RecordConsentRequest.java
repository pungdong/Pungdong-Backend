package com.diving.pungdong.consent.dto;

import com.diving.pungdong.consent.ConsentContext;
import lombok.*;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /consents 요청 — 한 화면에서 사용자가 체크한 약관들을 한 번에 기록.
 * 예: 강사신청 화면에서 [개인정보 v1, 고유식별정보 v1, 서비스약관 v2] 동의.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RecordConsentRequest {

    /** 동의를 수집한 화면 (signup / identity_verification / instructor_application / payment). */
    @NotNull
    private ConsentContext context;

    @NotEmpty
    @Valid
    private List<AgreementRef> agreements;
}
