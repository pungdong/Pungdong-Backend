package com.diving.pungdong.instructorapplication.dto;

import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 강사 신청 1건 (종목별). {@code GET /instructor-applications/me} 는 이 항목의 <b>리스트</b>를
 * 돌려준다 — 신청한 종목마다 한 항목. 특정 종목 미신청은 리스트에 없음(= FE 가 "신청하기" 노출).
 * CollectionModel 키 = "applications".
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "applications")
public class MyInstructorApplicationResponse {

    private String disciplineCode;
    /** "SUBMITTED" | "APPROVED" | "REJECTED" */
    private String status;

    /** 자격증 목록 (단체+이미지). 자격증 불필요 종목은 빈 목록. */
    private List<ApplicationCertificateDto> certificates;
    /** (선택) 다이빙보험 저장 참조 key — 재제출 시 그대로 보냄(라운드트립). 없으면 null. */
    private String insuranceFileKey;
    /** (선택) 보험 이미지 표시용 한시 presigned URL — 조회 시점 발급. insuranceFileKey 없으면 null. */
    private String insuranceViewUrl;
    private boolean identityVerified;
    private String rejectionReason;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}
