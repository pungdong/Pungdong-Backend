package com.diving.pungdong.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface UserNotificationJpaRepo extends JpaRepository<UserNotification, Long> {

    /**
     * ⚠️ {@code IdDesc} 타이브레이커가 필수다. 알림은 한 트랜잭션에서 여러 건 생길 수 있어
     * {@code created_at} 이 같은 행이 흔한데, 그때 정렬이 불확정이면 페이지 경계에서 같은 행이
     * 두 번 오거나 아예 건너뛰어진다(무한스크롤이 조용히 항목을 잃는다).
     */
    Page<UserNotification> findByRecipientAccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable pageable);

    Page<UserNotification> findByRecipientAccountIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
            Long accountId, Pageable pageable);

    long countByRecipientAccountIdAndReadAtIsNull(Long accountId);

    /** 소유권 검증을 쿼리에 내장 — 남의 알림 id 로는 조회 자체가 안 된다(→ 404 존재 숨김). */
    Optional<UserNotification> findByIdAndRecipientAccountId(Long id, Long accountId);

    /** 전체 읽음 — 행 단위 루프 금지, 벌크 UPDATE 한 방. 이미 읽은 행은 건드리지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserNotification n set n.readAt = :now "
            + "where n.recipientAccountId = :accountId and n.readAt is null")
    int markAllRead(@Param("accountId") Long accountId, @Param("now") OffsetDateTime now);
}
