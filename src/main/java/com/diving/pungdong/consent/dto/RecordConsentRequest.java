package com.diving.pungdong.consent.dto;

import com.diving.pungdong.consent.ConsentContext;
import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /consents 요청 — 한 화면에서 사용자가 체크한 약관들을 한 번에 기록.
 *
 * <p><b>약관 key 만 보낸다</b> (version 아님). 어떤 버전으로 기록할지는 BE 가 key 로 Sanity 의
 * 현재 버전을 조회해 전적으로 정한다 — FE 가 version 을 보내면 "FE 가 버전을 정한다" 는 오해를
 * 부르므로 계약에서 아예 뺀다. 기록된 version 은 응답({@link RecordConsentResponse})으로 받는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RecordConsentRequest {

    /** 동의를 수집한 화면 (signup / identity_verification / instructor_application / payment). */
    @NotNull
    private ConsentContext context;

    /** 동의한 약관들의 key (예: ["privacy_collect", "unique_id_ci_di"]). 최소 1건. */
    @NotEmpty
    private List<@NotBlank String> keys;
}
