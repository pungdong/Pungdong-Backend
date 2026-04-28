package com.diving.pungdong.usecase;

import com.diving.pungdong.domain.account.Account;
import com.diving.pungdong.domain.account.DeviceType;
import com.diving.pungdong.domain.account.FirebaseToken;
import com.diving.pungdong.domain.account.Gender;
import com.diving.pungdong.domain.account.Role;
import com.diving.pungdong.domain.notification.NotificationOutbox;
import com.diving.pungdong.domain.notification.NotificationStatus;
import com.diving.pungdong.domain.notification.NotificationType;
import com.diving.pungdong.domain.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.repo.AccountJpaRepo;
import com.diving.pungdong.repo.FirebaseTokenJpaRepo;
import com.diving.pungdong.repo.notification.NotificationOutboxJpaRepo;
import com.diving.pungdong.service.account.FirebaseTokenService;
import com.diving.pungdong.service.notification.NotificationDeliveryWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationOutboxFlowTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired NotificationOutboxJpaRepo outboxRepo;
    @Autowired NotificationDeliveryWorker deliveryWorker;
    @Autowired FirebaseTokenService firebaseTokenService;
    @Autowired FirebaseTokenJpaRepo firebaseTokenRepo;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired TransactionTemplate transactionTemplate;

    private void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    @AfterEach
    void cleanUp() {
        outboxRepo.deleteAll();
        firebaseTokenRepo.deleteAll();
        accountRepo.deleteAll();
    }

    private Account persistAccount(String email) {
        return accountRepo.save(Account.builder()
                .email(email)
                .password("encoded")
                .nickName("user-" + email)
                .birth("2000-01-01")
                .gender(Gender.MALE)
                .roles(Set.of(Role.STUDENT))
                .build());
    }

    @Test
    @DisplayName("ReservationCreatedEvent 발행 시 outbox에 instructor 수신 PENDING 행이 생성됨")
    void reservationCreatedEvent_writesOutboxRowForInstructor() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");

        inTransaction(() -> eventPublisher.publishEvent(ReservationCreatedEvent.builder()
                .instructorAccountId(instructor.getId())
                .studentAccountId(student.getId())
                .lectureId(100L)
                .scheduleId(200L)
                .studentNickname("student-nick")
                .lectureTitle("프리다이빙 입문")
                .build()));

        List<NotificationOutbox> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.get(0);
        assertThat(row.getType()).isEqualTo(NotificationType.RESERVATION_CREATED);
        assertThat(row.getRecipientAccountId()).isEqualTo(instructor.getId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getPayload()).contains("프리다이빙 입문");
    }

    @Test
    @DisplayName("발송 워커가 PENDING 행을 SENT로 전환 (Phase 2-A 스텁 동작)")
    void deliveryWorker_marksPendingAsSent() {
        Account instructor = persistAccount("instructor2@test.com");
        Account student = persistAccount("student2@test.com");
        inTransaction(() -> eventPublisher.publishEvent(ReservationCreatedEvent.builder()
                .instructorAccountId(instructor.getId())
                .studentAccountId(student.getId())
                .lectureId(101L)
                .scheduleId(201L)
                .studentNickname("nick")
                .lectureTitle("강의")
                .build()));

        Long outboxId = outboxRepo.findAll().get(0).getId();

        deliveryWorker.deliver(outboxId);

        NotificationOutbox afterDelivery = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(afterDelivery.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(afterDelivery.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("FirebaseToken upsert: 같은 token을 다른 account로 등록하면 account_id가 갱신됨 (행 추가 X)")
    void firebaseToken_upsertOnExistingToken() {
        Account first = persistAccount("first@test.com");
        Account second = persistAccount("second@test.com");
        String sharedDeviceToken = "device-token-shared-fcm-id";

        firebaseTokenService.register(first, sharedDeviceToken, DeviceType.ANDROID);
        firebaseTokenService.register(second, sharedDeviceToken, DeviceType.ANDROID);

        List<FirebaseToken> all = firebaseTokenRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getAccount().getId()).isEqualTo(second.getId());
        assertThat(all.get(0).getToken()).isEqualTo(sharedDeviceToken);
    }
}
