package com.diving.pungdong.moderation.dto;

import com.diving.pungdong.moderation.ReportReason;
import com.diving.pungdong.moderation.ReportStatus;
import com.diving.pungdong.moderation.ReportTargetType;
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
     * 어드민 목록에서만 채워진다 — <b>조치 대상이 누구인지</b>(글·댓글·강의·메시지의 작성자).
     * 대상이 이미 지워졌으면 키가 없다.
     *
     * <p>대상 타입이 넷으로 늘면서 필요해졌다. 없으면 어드민이 "누구를 조치하는지" 를 알려면 매번
     * 대상 화면을 열어야 하고, 같은 사람이 여러 신고에 걸려 있는지 큐에서 알아볼 수 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String targetAuthorNickName;

    /**
     * 신고 대상의 본문 미리보기 — 어드민이 목록에서 바로 판단할 수 있게. 대상이 이미 삭제됐으면 null.
     *
     * <p>목록에서 대상마다 원문을 열어봐야 하면 검토가 느려진다. 다만 전문을 싣지는 않는다 —
     * 큐는 훑는 화면이고 판단이 애매하면 상세로 들어가면 된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String targetPreview;

    /**
     * 이 <b>작성자</b>에 대한 누적 신고 수(상태·대상 종류 무관). 어드민 목록에서만 채워진다.
     *
     * <p>대상이 넷으로 흩어져 있어 <b>같은 강사의 여러 강의에 걸친 반복 신고가 서로 만나지 못했다</b> —
     * 강의 셋에 1건씩이면 어느 행에서도 안 걸린다. 이 숫자가 "한 번 걸린 사람" 과 "계속 걸리는 사람" 을
     * 가르고, {@code ?targetAuthorNickName=} 필터로 이어지는 진입점이다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Long targetAuthorReportCount;

    /**
     * <b>강의 신고</b>에서만 채워진다 — 신고자가 그 강의를 <b>신청한 적 있는가</b>(상태 무관).
     * 그 외 대상에서는 키가 없다.
     *
     * <p>"수강한 적 없는 사람의 강의 신고" 와 "실제 수강생의 분쟁 신고" 는 완전히 다른 사건인데
     * 신고 본문만으로는 구분이 안 된다. 회차 일자·결제/환불 이력까지는 싣지 않는다 — 큐는 훑는
     * 화면이고, 판단이 갈리는 건 이 한 비트다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Boolean reporterEnrolled;

    /**
     * 어드민이 처리하며 남긴 메모. 어드민 목록에서만 채워진다.
     *
     * <p>조치({@code ACTIONED})가 대상별로 무거워서(강의면 사실상 판매 중단) 1:1 분쟁은 대개 기각으로
     * 끝난다 — 메모가 없으면 "따로 경고함" 과 "문제없음" 이 같은 행으로 보인다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String adminNote;
}
