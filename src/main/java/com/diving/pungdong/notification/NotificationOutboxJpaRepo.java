package com.diving.pungdong.notification;

import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxJpaRepo extends JpaRepository<NotificationOutbox, Long> {

    List<NotificationOutbox> findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
            List<NotificationStatus> statuses,
            LocalDateTime now,
            Pageable pageable);

    int deleteByStatusAndCreatedAtBefore(NotificationStatus status, LocalDateTime threshold);
}
