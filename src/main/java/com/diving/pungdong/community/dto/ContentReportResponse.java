package com.diving.pungdong.community.dto;

import com.diving.pungdong.community.ReportReason;
import com.diving.pungdong.community.ReportStatus;
import com.diving.pungdong.community.ReportTargetType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;

/**
 * 신고 1건. 접수 응답이자 어드민 큐의 한 행이다.
 *
 * <p>신고자 정보는 <b>어드민 목록에만</b> 실린다 — 접수 응답에서 자기 닉네임을 되돌려 받을 이유가 없고,
 * 신고는 성격상 대상자에게 노출되면 안 되는 정보다.
 */
@Getter
@Builder
@Relation(collectionRelation = "reports")
public class ContentReportResponse {

    private final Long id;
    private final ReportTargetType targetType;
    private final Long targetId;
    private final ReportReason reason;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String detail;

    private final ReportStatus status;
    private final OffsetDateTime createdAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final OffsetDateTime handledAt;

    /** 어드민 목록에서만 채워진다(신고자 닉네임). 접수 응답에서는 키가 없다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String reporterNickName;

    /**
     * 신고 대상의 본문 미리보기 — 어드민이 목록에서 바로 판단할 수 있게. 대상이 이미 삭제됐으면 null.
     *
     * <p>목록에서 대상마다 원문을 열어봐야 하면 검토가 느려진다. 다만 전문을 싣지는 않는다 —
     * 큐는 훑는 화면이고 판단이 애매하면 상세로 들어가면 된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String targetPreview;
}
