package com.diving.pungdong.community.dto;

import com.diving.pungdong.community.ReportReason;
import com.diving.pungdong.community.ReportTargetType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** 신고 접수. 게시물·댓글 두 종류를 {@code targetType} + {@code targetId} 로 가리킨다. */
@Getter @Setter
@NoArgsConstructor
public class ContentReportRequest {

    @NotNull(message = "신고 대상을 선택해주세요.")
    private ReportTargetType targetType;

    @NotNull(message = "신고 대상을 선택해주세요.")
    private Long targetId;

    @NotNull(message = "신고 사유를 선택해주세요.")
    private ReportReason reason;

    /**
     * 자유 설명. {@link ReportReason#OTHER} 일 때만 필수 — 서비스가 검증한다.
     *
     * <p>DTO 애노테이션으로는 "다른 필드 값에 따라 필수" 를 표현할 수 없어 서비스로 넘겼다.
     * 사유가 "기타" 인데 설명이 없으면 어드민이 판단할 근거가 아무것도 없다.
     */
    @Size(max = 500, message = "설명은 500자까지 쓸 수 있어요.")
    private String detail;
}
