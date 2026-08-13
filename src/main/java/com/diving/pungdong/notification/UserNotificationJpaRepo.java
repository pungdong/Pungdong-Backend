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

    Page<UserNotification> findByRecipientAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    Page<UserNotification> findByRecipientAccountIdAndReadAtIsNullOrderByCreatedAtDesc(
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
