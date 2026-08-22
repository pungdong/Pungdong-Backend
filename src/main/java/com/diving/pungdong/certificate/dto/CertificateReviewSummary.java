package com.diving.pungdong.certificate.dto;

import com.diving.pungdong.certificate.CertificateReviewKind;
import com.diving.pungdong.certificate.CertificateReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 어드민 검수 큐의 한 행. PagedModel 키 = "reviews". 세 종류(NEW/ADDITIONAL/RE_VERIFY)가 한 목록에 섞인다 —
 * kind 로 구분하고, NEW 는 {@code applicationId}, 나머지는 {@code certificateId} 가 채워진다.
 */
@Getter
@Builder
@AllArgsConstructor
@Relation(collectionRelation = "reviews")
public class CertificateReviewSummary {
    private final Long reviewId;
    private final CertificateReviewKind kind;
    private final Long applicationId;
    private final Long certificateId;
    private final Long accountId;
    private final String nickName;
    private final String email;
    private final String disciplineCode;
    /** 대상 자격증의 단체 코드(중복 제거) — NEW 는 첨부 전부, 나머지는 그 한 장. */
    private final List<String> organizationCodes;
    private final CertificateReviewStatus status;
    private final OffsetDateTime requestedAt;
    private final OffsetDateTime reviewedAt;
    /**
     * 승인 ∧ 자격증 필수 종목인데 살아있는 검증({VERIFIED, PENDING}) 강사레벨 자격증이 <b>0 장</b> — 마지막 VERIFIED 를
     * 재검수에 올렸다가 반려된 경우(인정한 구멍). 자동 회수 없음, 어드민이 본다.
     */
    private final boolean verifiedCertificateMissing;
}
