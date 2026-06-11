package com.diving.pungdong.consent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 동의를 수집하는 화면. Sanity {@code term.contexts} 의 값과 같은 어휘를 공유한다
 * (FE 는 같은 문자열로 Sanity 약관을 조회하고 BE 에 동의를 기록).
 *
 * <p>DB 에는 enum 이름(예: {@code INSTRUCTOR_APPLICATION})으로, JSON 입출력은 lowercase
 * snake({@code instructor_application})로 — Sanity 어휘와 1:1.
 */
public enum ConsentContext {
    SIGNUP("signup"),
    IDENTITY_VERIFICATION("identity_verification"),
    INSTRUCTOR_APPLICATION("instructor_application"),
    PAYMENT("payment");

    private final String code;

    ConsentContext(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static ConsentContext from(String value) {
        if (value != null) {
            for (ConsentContext c : values()) {
                if (c.code.equalsIgnoreCase(value) || c.name().equalsIgnoreCase(value)) {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("unknown consent context: " + value);
    }
}
