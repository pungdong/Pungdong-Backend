package com.diving.pungdong.consent.dto;

import lombok.*;

import java.util.List;

/** POST /consents 응답(201) — 기록된 동의 건수 + 어떤 (key,version) 들이 기록됐는지. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RecordConsentResponse {
    private int recorded;
    private List<AgreementRef> agreements;
}
