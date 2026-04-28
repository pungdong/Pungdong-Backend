package com.diving.pungdong.repo.notification;

import com.diving.pungdong.domain.notification.NotificationOutbox;
import com.diving.pungdong.domain.notification.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationOutboxJpaRepo extends JpaRepository<NotificationOutbox, Long> {

    List<NotificationOutbox> findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
            List<NotificationStatus> statuses,
            LocalDateTime now,
            Pageable pageable);
}
