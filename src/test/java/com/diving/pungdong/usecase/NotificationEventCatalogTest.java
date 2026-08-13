package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.notification.NotificationCategory;
import com.diving.pungdong.notification.NotificationOutbox;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.UserNotification;
import com.diving.pungdong.notification.UserNotificationJpaRepo;
import com.diving.pungdong.notification.event.EnrollmentAcceptedEvent;
import com.diving.pungdong.notification.event.EnrollmentExpiredEvent;
import com.diving.pungdong.notification.event.EnrollmentRejectedEvent;
import com.diving.pungdong.notification.event.EnrollmentSlotsProposedEvent;
import com.diving.pungdong.notification.event.EnrollmentSubmittedEvent;
import com.diving.pungdong.notification.event.PaymentCompletedEvent;
import com.diving.pungdong.notification.event.RefundCompletedEvent;
import com.diving.pungdong.notification.event.RoundCompletedEvent;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 알림 이벤트 카탈로그 사양 — 이벤트 하나가 <b>누구에게</b> · <b>어떤 타입/채널로</b> · <b>어떤 라우팅
 * 좌표를 실어</b> 나가는지를 고정한다. {@code @DisplayName} 을 위에서 아래로 읽으면 카탈로그가 된다.
 *
 * <p>여기서 잠그는 것이 <b>FE 계약</b>이다 — 앱은 {@code data.type} 으로 화면을 고르고
 * {@code data.courseId/enrollmentId/roundId} 로 대상을 찾는다. 키 이름이 조용히 바뀌면
 * 라우팅이 no-op 이 되고(모르는 type → default) 아무도 못 알아챈다.
 *
 * <p>발행 <b>지점</b>(어느 서비스 메서드에서 쏘는지)은 각 도메인의 use-case 테스트가 이미 그 경로를
 * 지나가므로 여기서는 <b>이벤트 → outbox/알림함 매핑</b>만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationEventCatalogTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired NotificationOutboxJpaRepo outboxRepo;
    @Autowired UserNotificationJpaRepo userNotificationRepo;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean FcmGateway fcmGateway;

    @BeforeEach
    void gatewaySuccess() {
        given(fcmGateway.send(any(), any(), any(), any(), any())).willReturn(SendResult.SUCCESS);
    }

    @AfterEach
    void cleanUp() {
        outboxRepo.deleteAll();
        userNotificationRepo.deleteAll();
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

    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(s -> eventPublisher.publishEvent(event));
    }

    /**
     * 이 테스트가 만든 계정 앞으로 발행된 단 한 건의 outbox 행.
     *
     * <p>⚠️ <b>전체 행 수로 단언하지 않는다.</b> 이제 수강신청·수락·결제 등 다른 use-case 테스트도
     * 알림을 발행하므로, 같은 컨텍스트를 공유하는 앞선 테스트 클래스가 남긴 행이 있으면 전역
     * 카운트가 흔들린다. 수신자로 좁혀 이 테스트를 순서 독립으로 만든다.
     */
    private NotificationOutbox onlyOutbox(Account recipient) {
        List<NotificationOutbox> mine = outboxRepo.findAll().stream()
                .filter(r -> recipient.getId().equals(r.getRecipientAccountId()))
                .collect(Collectors.toList());
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    /** 같은 이유로 수신자 기준. */
    private UserNotification onlyInbox(Account recipient) {
        List<UserNotification> mine = userNotificationRepo.findAll().stream()
                .filter(r -> recipient.getId().equals(r.getRecipientAccountId()))
                .collect(Collectors.toList());
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    /** outbox payload 와 알림함 data 가 같은 라우팅 좌표를 담고 있는지. */
    private void assertRoutingData(Account recipient, String... expectedFragments) {
        String payload = onlyOutbox(recipient).getPayload();
        String inboxData = onlyInbox(recipient).getData();
        for (String fragment : expectedFragments) {
            assertThat(payload).contains(fragment);
            assertThat(inboxData).contains(fragment);
        }
    }

    @Test
    @DisplayName("N1 강사 수락 → 학생에게 ENROLLMENT_ACCEPTED(reservation 채널), courseId/enrollmentId/roundId 동봉")
    void enrollmentAccepted() {
        Account student = persistAccount("student@test.com");

        publish(EnrollmentAcceptedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").instructorNickName("김강사")
                .build());

        NotificationOutbox row = onlyOutbox(student);
        assertThat(row.getType()).isEqualTo(NotificationType.ENROLLMENT_ACCEPTED);
        assertThat(row.getType().getCategory()).isEqualTo(NotificationCategory.RESERVATION);
        assertThat(row.getRecipientAccountId()).isEqualTo(student.getId());
        assertThat(row.getPayload()).contains("김강사님이 프리다이빙 입문 신청을 수락했어요");
        assertRoutingData(student, "\"type\":\"ENROLLMENT_ACCEPTED\"",
                "\"courseId\":\"7\"", "\"enrollmentId\":\"12\"", "\"roundId\":\"34\"");
    }

    @Test
    @DisplayName("N2 강사 거절 → 학생에게 ENROLLMENT_REJECTED. body 가 전액 환불을 안내한다(별도 환불 알림을 안 보내는 근거)")
    void enrollmentRejected() {
        Account student = persistAccount("student@test.com");

        publish(EnrollmentRejectedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").instructorNickName("김강사")
                .build());

        assertThat(onlyOutbox(student).getType()).isEqualTo(NotificationType.ENROLLMENT_REJECTED);
        assertThat(onlyOutbox(student).getPayload()).contains("전액 환불됩니다");
    }

    @Test
    @DisplayName("N3 강사 일정 제안 → 학생에게 ENROLLMENT_SLOTS_PROPOSED, 선택을 유도하는 문구")
    void enrollmentSlotsProposed() {
        Account student = persistAccount("student@test.com");

        publish(EnrollmentSlotsProposedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").instructorNickName("김강사")
                .build());

        assertThat(onlyOutbox(student).getType()).isEqualTo(NotificationType.ENROLLMENT_SLOTS_PROPOSED);
        assertThat(onlyOutbox(student).getPayload()).contains("확인하고 선택해 주세요");
    }

    @Test
    @DisplayName("N4 미결제 만료(paid=false) → 환불 문구 없이 '결제 기한이 지나' 로 안내")
    void enrollmentExpiredUnpaid() {
        Account student = persistAccount("student@test.com");

        publish(EnrollmentExpiredEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").instructorNickName("김강사")
                .paid(false)
                .build());

        assertThat(onlyOutbox(student).getType()).isEqualTo(NotificationType.ENROLLMENT_EXPIRED);
        assertThat(onlyOutbox(student).getPayload()).contains("결제 기한이 지나");
        assertThat(onlyOutbox(student).getPayload()).doesNotContain("환불되었어요");
    }

    @Test
    @DisplayName("N5 결제완료 무응답 만료(paid=true) → 자동환불을 함께 안내한다")
    void enrollmentExpiredPaid() {
        Account student = persistAccount("student@test.com");

        publish(EnrollmentExpiredEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").instructorNickName("김강사")
                .paid(true)
                .build());

        // 코스명이 들어가야 한다 — 여러 강의를 신청한 유저는 강사 이름만으로 어느 건인지 못 가린다.
        assertThat(onlyOutbox(student).getPayload()).contains("프리다이빙 입문 신청이 취소되고 전액 환불되었어요");
    }

    @Test
    @DisplayName("N6 학생 신청 → 강사에게 ENROLLMENT_SUBMITTED (수신자가 강사다)")
    void enrollmentSubmitted() {
        Account instructor = persistAccount("instructor@test.com");

        publish(EnrollmentSubmittedEvent.builder()
                .instructorAccountId(instructor.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문").studentNickName("이학생")
                .build());

        NotificationOutbox row = onlyOutbox(instructor);
        assertThat(row.getType()).isEqualTo(NotificationType.ENROLLMENT_SUBMITTED);
        assertThat(row.getRecipientAccountId()).isEqualTo(instructor.getId());
        assertThat(row.getPayload()).contains("이학생님이 프리다이빙 입문을 신청했어요");
    }

    @Test
    @DisplayName("N7 회차 완료 → 학생에게 ROUND_COMPLETED, 후기를 유도한다")
    void roundCompleted() {
        Account student = persistAccount("student@test.com");

        publish(RoundCompletedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L)
                .courseTitle("프리다이빙 입문")
                .build());

        assertThat(onlyOutbox(student).getType()).isEqualTo(NotificationType.ROUND_COMPLETED);
        assertThat(onlyOutbox(student).getPayload()).contains("후기를 남겨주세요");
    }

    @Test
    @DisplayName("N8 결제 완료 → 학생에게 PAYMENT_COMPLETED(payment 채널), 금액은 천단위 구분 + orderId 동봉")
    void paymentCompleted() {
        Account student = persistAccount("student@test.com");

        publish(PaymentCompletedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L).orderId(56L)
                .courseTitle("프리다이빙 입문").amount(1234567)
                .build());

        NotificationOutbox row = onlyOutbox(student);
        assertThat(row.getType()).isEqualTo(NotificationType.PAYMENT_COMPLETED);
        // payment 채널은 앱에 이미 만들어져 있던 빈 채널이다 — 신설이 아니라 첫 사용이라 앱 변경이 필요 없다.
        assertThat(row.getType().getCategory()).isEqualTo(NotificationCategory.PAYMENT);
        assertThat(row.getPayload()).contains("1,234,567원 결제가 완료되었어요");
        assertRoutingData(student, "\"orderId\":\"56\"");
    }

    @Test
    @DisplayName("N9 환불 완료 → 학생에게 REFUND_COMPLETED(payment 채널)")
    void refundCompleted() {
        Account student = persistAccount("student@test.com");

        publish(RefundCompletedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(7L).enrollmentId(12L).roundId(34L).orderId(56L)
                .courseTitle("프리다이빙 입문").amount(50000)
                .build());

        NotificationOutbox row = onlyOutbox(student);
        assertThat(row.getType()).isEqualTo(NotificationType.REFUND_COMPLETED);
        assertThat(row.getType().getCategory()).isEqualTo(NotificationCategory.PAYMENT);
        assertThat(row.getPayload()).contains("50,000원이 환불되었어요");
    }

    @Test
    @DisplayName("N10 라우팅 좌표가 null 이면 그 키를 아예 싣지 않는다 (앱이 Number(\"null\")로 NaN 을 만들지 않게)")
    void nullIdsAreOmitted() {
        Account student = persistAccount("student@test.com");

        publish(RoundCompletedEvent.builder()
                .studentAccountId(student.getId())
                .courseId(null).enrollmentId(null).roundId(34L)
                .courseTitle("프리다이빙 입문")
                .build());

        assertThat(onlyInbox(student).getData()).doesNotContain("null");
        assertThat(onlyInbox(student).getData()).contains("\"roundId\":\"34\"");
    }

    @Test
    @DisplayName("N11 모든 신규 타입 이름이 32자 이하다 (notification_outbox.type 이 varchar(32))")
    void typeNamesFitColumn() {
        for (NotificationType type : NotificationType.values()) {
            assertThat(type.name().length())
                    .as("%s 이 varchar(32) 를 넘는다", type.name())
                    .isLessThanOrEqualTo(32);
        }
    }
}
