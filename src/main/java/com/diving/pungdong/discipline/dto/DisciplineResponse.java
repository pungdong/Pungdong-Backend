package com.diving.pungdong.discipline.dto;

import com.diving.pungdong.discipline.Discipline;
import lombok.*;

/** GET /disciplines 응답 항목 — 홈 셀렉터 / 강사 신청 종목 선택용. */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class DisciplineResponse {
    private String code;
    private String name;
    private boolean requiresCertification;
    private int sortOrder;

    public static DisciplineResponse from(Discipline d) {
        return DisciplineResponse.builder()
                .code(d.getCode())
                .name(d.getName())
                .requiresCertification(d.isRequiresCertification())
                .sortOrder(d.getSortOrder())
                .build();
    }
}
