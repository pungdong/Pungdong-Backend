package com.diving.pungdong.instructorapplication.dto;

import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.List;

/** 어드민 대기 목록의 한 행 (PII 최소화 — 상세는 detail 에서). PagedModel 키 = "applications". */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "applications")
public class InstructorApplicationSummary {
    private Long applicationId;
    private Long accountId;
    private String nickName;
    private String email;
    private String disciplineCode;
    /** 첨부 자격증의 단체 코드들 (중복 제거) — 목록에서 한눈에. */
    private List<String> organizationCodes;
    private InstructorApplicationStatus status;
    private OffsetDateTime submittedAt;
}
