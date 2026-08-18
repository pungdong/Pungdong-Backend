package com.diving.pungdong.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 어드민 큐 — 상태·항목 두 축을 <b>한 쿼리</b>로. 파라미터가 {@code null} 이면 그 축은 안 건다.
     *
     * <p>파생 메서드 이름을 축 조합마다 만들면 (전체·상태·항목·둘) 넷이 되고, 축이 하나 더 늘면
     * 여덟이 된다. 어드민 화면이 "커뮤니티글 / 강의 / 채팅" 탭으로 갈리는 게 이 피처의 요구라
     * 조합은 계속 늘어난다 — 처음부터 nullable 파라미터로 받는다.
     */
    @Query("select r from ContentReport r "
            + "where (:status is null or r.status = :status) "
            + "and (:targetType is null or r.targetType = :targetType) "
            + "order by r.createdAt desc, r.id desc")
    Page<ContentReport> findQueue(@Param("status") ReportStatus status,
                                  @Param("targetType") ReportTargetType targetType,
                                  Pageable pageable);

    /** 어드민 탭 뱃지용 상태별 건수. */
    long countByStatus(ReportStatus status);

    /**
     * 어드민이 조치한 이력이 있는 대상인가 — <b>작성자가 숨김을 되돌려 조치를 무효화하지 못하게</b>
     * 막는 데 쓴다. 조치된 글은 작성자가 다시 공개할 수 없다.
     */
    boolean existsByTargetTypeAndTargetIdAndStatus(ReportTargetType targetType,
                                                   Long targetId,
                                                   ReportStatus status);
}
