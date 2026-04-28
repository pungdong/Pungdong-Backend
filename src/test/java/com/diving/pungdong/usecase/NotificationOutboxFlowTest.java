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
import com.diving.pungdong.service.notification.fcm.FcmGateway;
import com.diving.pungdong.service.notification.fcm.FcmGateway.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

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

    @MockBean FcmGateway fcmGateway;

    @BeforeEach
    void defaultGatewaySuccess() {
        given(fcmGateway.send(any(), any(), any(), any())).willReturn(SendResult.SUCCESS);
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

    private void publishReservationCreated(Account instructor, Account student) {
        transactionTemplate.executeWithoutResult(s ->
                eventPublisher.publishEvent(ReservationCreatedEvent.builder()
                        .instructorAccountId(instructor.getId())
                        .studentAccountId(student.getId())
                        .lectureId(100L)
                        .scheduleId(200L)
                        .studentNickname(student.getNickName())
                        .lectureTitle("프리다이빙 입문")
                        .build()));
    }

    @Test
    @DisplayName("ReservationCreatedEvent 발행 시 outbox에 instructor 수신 PENDING 행이 생성됨 (payload는 title/body 구조)")
    void reservationCreatedEvent_writesOutboxRowForInstructor() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");

        publishReservationCreated(instructor, student);

        List<NotificationOutbox> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.get(0);
        assertThat(row.getType()).isEqualTo(NotificationType.RESERVATION_CREATED);
        assertThat(row.getRecipientAccountId()).isEqualTo(instructor.getId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(row.getPayload()).contains("프리다이빙 입문");
        assertThat(row.getPayload()).contains("\"title\"");
        assertThat(row.getPayload()).contains("\"body\"");
    }

    @Test
    @DisplayName("발송 워커: 토큰 등록된 수신자, FCM 성공 → SENT")
    void deliveryWorker_marksSent_whenFcmSucceeds() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");
        firebaseTokenService.register(instructor, "device-token-A", DeviceType.ANDROID);
        publishReservationCreated(instructor, student);
        Long outboxId = outboxRepo.findAll().get(0).getId();

        deliveryWorker.deliver(outboxId);

        NotificationOutbox after = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("발송 워커: 수신자에게 등록된 토큰이 없으면 즉시 GAVE_UP")
    void deliveryWorker_givesUp_whenRecipientHasNoTokens() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");
        publishReservationCreated(instructor, student);
        Long outboxId = outboxRepo.findAll().get(0).getId();

        deliveryWorker.deliver(outboxId);

        NotificationOutbox after = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.GAVE_UP);
        assertThat(after.getLastError()).contains("no registered firebase tokens");
    }

    @Test
    @DisplayName("발송 워커: FCM 영구 실패(UNREGISTERED 등) → 토큰 삭제 + GAVE_UP")
    void deliveryWorker_deletesToken_onPermanentFailure() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");
        firebaseTokenService.register(instructor, "dead-token", DeviceType.ANDROID);
        publishReservationCreated(instructor, student);
        Long outboxId = outboxRepo.findAll().get(0).getId();

        given(fcmGateway.send(eq("dead-token"), any(), any(), any()))
                .willReturn(SendResult.PERMANENT_FAILURE);

        deliveryWorker.deliver(outboxId);

        NotificationOutbox after = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.GAVE_UP);
        assertThat(firebaseTokenRepo.findByToken("dead-token")).isEmpty();
    }

    @Test
    @DisplayName("발송 워커: FCM 일시 실패 → 토큰 보존 + FAILED + next_attempt_at 미래로 스케줄")
    void deliveryWorker_schedulesRetry_onTransientFailure() {
        Account instructor = persistAccount("instructor@test.com");
        Account student = persistAccount("student@test.com");
        firebaseTokenService.register(instructor, "flaky-token", DeviceType.ANDROID);
        publishReservationCreated(instructor, student);
        Long outboxId = outboxRepo.findAll().get(0).getId();
        LocalDateTime before = LocalDateTime.now();

        given(fcmGateway.send(eq("flaky-token"), any(), any(), any()))
                .willReturn(SendResult.TRANSIENT_FAILURE);

        deliveryWorker.deliver(outboxId);

        NotificationOutbox after = outboxRepo.findById(outboxId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getNextAttemptAt()).isAfter(before);
        assertThat(firebaseTokenRepo.findByToken("flaky-token")).isPresent();
    }

    @Test
    @DisplayName("Retention: deleteByStatusAndCreatedAtBefore는 오래된 SENT만 지우고 FAILED/GAVE_UP 및 최근 SENT는 보존")
    void retention_deletesOnlyOldSentRows() {
        Account recipient = persistAccount("recipient@test.com");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime old = now.minusDays(40);
        LocalDateTime recent = now.minusDays(5);

        outboxRepo.save(buildOutbox(recipient, NotificationStatus.SENT, old, "old-sent"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.SENT, recent, "recent-sent"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.FAILED, old, "old-failed"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.GAVE_UP, old, "old-gave-up"));
        assertThat(outboxRepo.findAll()).hasSize(4);

        LocalDateTime threshold = now.minusDays(30);
        int deleted = transactionTemplate.execute(status ->
                outboxRepo.deleteByStatusAndCreatedAtBefore(NotificationStatus.SENT, threshold));

        assertThat(deleted).isEqualTo(1);
        List<NotificationOutbox> remaining = outboxRepo.findAll();
        assertThat(remaining).hasSize(3);
        assertThat(remaining)
                .extracting(NotificationOutbox::getStatus)
                .containsExactlyInAnyOrder(
                        NotificationStatus.SENT,
                        NotificationStatus.FAILED,
                        NotificationStatus.GAVE_UP);
        assertThat(remaining)
                .filteredOn(r -> r.getStatus() == NotificationStatus.SENT)
                .singleElement()
                .extracting(NotificationOutbox::getCreatedAt)
                .matches(t -> ((LocalDateTime) t).isAfter(threshold));
    }

    private NotificationOutbox buildOutbox(Account recipient, NotificationStatus status,
                                           LocalDateTime createdAt, String marker) {
        return NotificationOutbox.builder()
                .type(NotificationType.RESERVATION_CREATED)
                .recipientAccountId(recipient.getId())
                .payload("{\"title\":\"" + marker + "\",\"body\":\"x\"}")
                .status(status)
                .attempts(0)
                .nextAttemptAt(createdAt)
                .createdAt(createdAt)
                .build();
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
