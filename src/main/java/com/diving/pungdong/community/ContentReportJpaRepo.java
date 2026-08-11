package com.diving.pungdong.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 신고 접수·어드민 큐 조회. */
public interface ContentReportJpaRepo extends JpaRepository<ContentReport, Long> {

    /** 중복 신고 판정 — 이미 있으면 새로 만들지 않고 그대로 200 으로 응답한다(멱등). */
    Optional<ContentReport> findByTargetTypeAndTargetIdAndReporterId(ReportTargetType targetType,
                                                                     Long targetId,
                                                                     Long reporterId);

    /** 어드민 큐 — 상태 필터. {@code AdminInstructorApplicationController} 와 같은 모양으로 쓴다. */
    Page<ContentReport> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    Page<ContentReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 어드민 탭 뱃지용 상태별 건수. */
    long countByStatus(ReportStatus status);
}
