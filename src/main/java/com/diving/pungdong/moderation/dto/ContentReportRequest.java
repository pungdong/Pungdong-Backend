package com.diving.pungdong.moderation.dto;

import com.diving.pungdong.moderation.ReportReason;
import com.diving.pungdong.moderation.ReportTargetType;
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

    /**
     * 대상 id. {@code USER} 를 제외한 모든 타입에서 필수다 — 서비스가 타입별로 검증한다.
     *
     * <p>{@code USER} 만 예외인 이유는 {@link #targetNickName} 참고.
     */
    private Long targetId;

    /**
     * 신고할 사용자의 닉네임 — <b>{@code USER} 타입에서만 쓰고 그때는 필수</b>다.
     *
     * <p><b>계정 id 를 받지 않는다.</b> 순차 id 를 계약에 노출하면 증가시켜 전수 조회하는 길이 열린다
     * (루트 CLAUDE.md anti-IDOR). 공개 프로필·차단이 이미 닉네임을 식별자로 쓰고 있어 클라이언트가
     * 이미 들고 있는 값이기도 하다. 저장은 다른 타입과 똑같이 {@code target_id}(계정 id)로 하고,
     * 변환은 서버가 한다 — 폴리모픽 UNIQUE 제약이 id 축이라 저장 모양은 하나여야 한다.
     */
    @Size(max = 30, message = "닉네임이 너무 깁니다.")
    private String targetNickName;

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
