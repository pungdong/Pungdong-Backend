package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.account.FirebaseToken;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.FirebaseTokenJpaRepo;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import com.diving.pungdong.notification.UserNotificationJpaRepo;
import com.diving.pungdong.account.FirebaseTokenService;
import com.diving.pungdong.notification.NotificationDeliveryWorker;
import com.diving.pungdong.notification.NotificationDispatcher;
import com.diving.pungdong.notification.fcm.FcmGateway;
import com.diving.pungdong.notification.fcm.FcmGateway.SendResult;
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

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    @Autowired UserNotificationJpaRepo userNotificationRepo;
    @Autowired NotificationDeliveryWorker deliveryWorker;
    @Autowired FirebaseTokenService firebaseTokenService;
    @Autowired FirebaseTokenJpaRepo firebaseTokenRepo;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired TransactionTemplate transactionTemplate;
    @PersistenceContext EntityManager entityManager;

    @MockBean FcmGateway fcmGateway;

    @BeforeEach
    void defaultGatewaySuccess() {
        given(fcmGateway.send(any(), any(), any(), any(), any())).willReturn(SendResult.SUCCESS);
    }

    @AfterEach
    void cleanUp() {
        outboxRepo.deleteAll();
        // enqueue 가 outbox 와 알림함에 함께 쓰므로 여기도 지워야 테스트가 순서 독립이 된다.
        userNotificationRepo.deleteAll();
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

        given(fcmGateway.send(eq("dead-token"), any(), any(), any(), any()))
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
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        given(fcmGateway.send(eq("flaky-token"), any(), any(), any(), any()))
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
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime old = now.minusDays(40);
        OffsetDateTime recent = now.minusDays(5);

        outboxRepo.save(buildOutbox(recipient, NotificationStatus.SENT, old, "old-sent"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.SENT, recent, "recent-sent"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.FAILED, old, "old-failed"));
        outboxRepo.save(buildOutbox(recipient, NotificationStatus.GAVE_UP, old, "old-gave-up"));
        assertThat(outboxRepo.findAll()).hasSize(4);

        OffsetDateTime threshold = now.minusDays(30);
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
                .matches(t -> ((OffsetDateTime) t).isAfter(threshold));
    }

    private NotificationOutbox buildOutbox(Account recipient, NotificationStatus status,
                                           OffsetDateTime createdAt, String marker) {
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

    /**
     * 디스패처는 {@code @Profile("!test")} 라 테스트 컨텍스트에 빈이 없다. 스케줄러 없이 루프 로직만
     * 검증하면 되므로 실제 워커 빈(프록시라 REQUIRES_NEW 가 살아 있다)을 물려 직접 만든다.
     */
    private NotificationDispatcher newDispatcher() {
        return new NotificationDispatcher(outboxRepo, deliveryWorker);
    }

    /** payload 가 깨진 행 — {@code deliver()} 의 역직렬화가 IllegalStateException 을 던진다. */
    private NotificationOutbox persistPoisonRow(Account recipient, OffsetDateTime createdAt) {
        return outboxRepo.save(NotificationOutbox.builder()
                .type(NotificationType.RESERVATION_CREATED)
                .recipientAccountId(recipient.getId())
                .payload("{ this is not valid json")
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(createdAt)
                .createdAt(createdAt)
                .build());
    }

    @Test
    @DisplayName("P1 발송 중 예외가 나도 그 행만 실패 처리되고 배치의 나머지 행은 계속 발송된다 (poison pill 방지)")
    void dispatcher_continuesBatch_whenOneRowThrows() {
        Account recipient = persistAccount("recipient@test.com");
        firebaseTokenService.register(recipient, "token-ok", DeviceType.ANDROID);
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        // 깨진 행이 먼저 오게 한다 (픽업이 ORDER BY createdAt ASC 라 이게 선두).
        NotificationOutbox poison = persistPoisonRow(recipient, past);
        NotificationOutbox healthy = outboxRepo.save(buildOutbox(
                recipient, NotificationStatus.PENDING, past.plusMinutes(1), "healthy"));

        newDispatcher().dispatch();

        // 깨진 행: 예외를 삼키고 실패로 기록 — attempts 가 올라야 언젠가 GAVE_UP 으로 수렴한다.
        NotificationOutbox poisonAfter = outboxRepo.findById(poison.getId()).orElseThrow();
        assertThat(poisonAfter.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(poisonAfter.getAttempts()).isEqualTo(1);

        // 뒤 행: 정상 발송됨 — 예외가 루프를 끊지 않았다는 증거(이게 이 테스트의 핵심).
        NotificationOutbox healthyAfter = outboxRepo.findById(healthy.getId()).orElseThrow();
        assertThat(healthyAfter.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("P2 발송 예외가 반복되면 attempts가 쌓여 결국 GAVE_UP 으로 떨어진다 (큐 영구 정지 방지)")
    void dispatcher_eventuallyGivesUp_onRepeatedException() {
        Account recipient = persistAccount("recipient@test.com");
        firebaseTokenService.register(recipient, "token-ok", DeviceType.ANDROID);
        NotificationOutbox poison = persistPoisonRow(
                recipient, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        NotificationDispatcher dispatcher = newDispatcher();

        // ⚠️ 백오프로 nextAttemptAt 이 미래로 밀리므로 매 회 과거로 되돌려 "다음 틱"을 흉내낸다.
        // 되돌리는 건 nextAttemptAt <b>뿐</b>이다 — 예전엔 markFailedAndScheduleRetry 로 되돌렸는데
        // 그 메서드가 attempts 를 직접 올려서, 디스패처가 아무 것도 안 해도 통과하는 vacuous 테스트였다.
        // 이제 attempts 증가는 오직 dispatch() → recordDeliveryFailure 만이 만든다.
        int ticks = 0;
        while (outboxRepo.findById(poison.getId()).orElseThrow().getStatus() != NotificationStatus.GAVE_UP
                && ticks++ < 15) { // MAX_ATTEMPTS(10) 보다 넉넉히 — 상수는 package-private 라 직접 참조 불가
            rewindNextAttempt(poison.getId());
            dispatcher.dispatch();
        }

        NotificationOutbox after = outboxRepo.findById(poison.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.GAVE_UP);
        // 디스패처가 실제로 시도 횟수를 쌓았다는 증거(테스트가 대신 올린 게 아니다).
        assertThat(after.getAttempts()).isGreaterThanOrEqualTo(10);
    }

    /** {@code nextAttemptAt} 만 과거로 되돌린다 — attempts·status 는 건드리지 않는다. */
    private void rewindNextAttempt(Long outboxId) {
        transactionTemplate.executeWithoutResult(s -> entityManager
                .createQuery("update NotificationOutbox o set o.nextAttemptAt = :t where o.id = :id")
                .setParameter("t", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .setParameter("id", outboxId)
                .executeUpdate());
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
