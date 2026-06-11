package com.diving.pungdong.consent.dto;

import lombok.*;

/**
 * 기록된 동의 1건 — {@code (key, version)}. <b>응답 전용</b>: BE 가 key 로 정한 현재 version 을
 * 함께 돌려줘서, FE 가 "어떤 버전으로 기록됐는지" 확인할 수 있게 한다. (요청에는 version 이 없다.)
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AgreementRef {

    /** Sanity term 식별 키 (예: privacy_collect). */
    private String key;

    /** BE 가 기록한 약관 버전 (Sanity 현재값, 예: v1). */
    private String version;
}
