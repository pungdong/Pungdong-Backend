package com.diving.pungdong.enrollment.dto;

import lombok.*;

/** 강사 거절 요청 — 사유(선택). 거절은 복구 가능(학생이 다른 일정 재신청). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RejectRequest {
    private String reason;
}
