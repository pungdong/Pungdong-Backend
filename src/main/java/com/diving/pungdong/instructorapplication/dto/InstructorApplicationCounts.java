package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

/** 어드민 탭 뱃지용 상태별 건수 — 검수중(submitted)/통과(approved)/불통과(rejected). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationCounts {
    private long submitted;
    private long approved;
    private long rejected;
    private long total;
}
