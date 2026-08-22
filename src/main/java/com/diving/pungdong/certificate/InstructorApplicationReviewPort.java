package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.ApplicationReviewView;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검수 큐의 NEW 행(= 강사 신청)을 다룰 때 certificate 도메인이 instructorapplication 에 맡기는 일.
 * 승인/반려의 실체(권한 부여·상태머신)는 저쪽 소유라 여기선 위임만 한다. 의존 방향 유지용 포트
 * ({@link InstructorApprovalLookup} 과 같은 이유) — 구현은 저쪽 어댑터.
 */
public interface InstructorApplicationReviewPort {

    /** 신청 상세(본인확인 PII·보험·첨부 id) — NEW 행 상세 화면용. 없으면 empty. */
    Optional<ApplicationReviewView> view(Long applicationId);

    /** 여러 신청의 첨부 자격증 id 일괄 — 목록의 단체 칩용. */
    Map<Long, List<Long>> certificateIdsOf(Collection<Long> applicationIds);

    void approve(Long applicationId, Account reviewer);

    void reject(Long applicationId, Account reviewer, String reason);
}
