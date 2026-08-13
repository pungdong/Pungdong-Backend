package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.notification.NotificationOutboxJpaRepo;
import com.diving.pungdong.notification.NotificationStatus;
import com.diving.pungdong.notification.NotificationType;
import com.diving.pungdong.notification.UserNotification;
import com.diving.pungdong.notification.UserNotificationJpaRepo;
import com.diving.pungdong.notification.event.ReservationCreatedEvent;
import com.diving.pungdong.notification.fcm.FcmGateway;
import com.diving.pungdong.notification.fcm.FcmGateway.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인앱 알림함(수신함) 사양 — {@code @DisplayName} 을 위에서 아래로 읽으면 그대로 스펙이다.
 *
 * <p>S* 성공 / R* 권한 / V* 검증 / X* 트랜잭션. 실 H2 + 실 시큐리티 필터체인으로 돈다.
 * 외부 경계인 {@code FcmGateway} 만 mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationCenterUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired UserNotificationJpaRepo userNotificationRepo;
    @Autowired NotificationOutboxJpaRepo outboxRepo;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean FcmGateway fcmGateway;

    @BeforeEach
    void gatewaySuccess() {
        given(fcmGateway.send(any(), any(), any(), any(), any())).willReturn(SendResult.SUCCESS);
    }

    @AfterEach
    void cleanUp() {
        userNotificationRepo.deleteAll();
        outboxRepo.deleteAll();
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

    /** 이 레포의 필터는 Authorization 헤더에 <b>raw JWT</b> 를 받는다(Bearer 접두사 없음). */
    private String bearer(Account account) {
        return jwtTokenProvider.createAccessToken(
                String.valueOf(account.getId()), account.getRoles());
    }

    /** 실제 발행 경로로 알림 1건을 만든다 (enqueue 이중 INSERT 를 그대로 태운다). */
    private void publishOneNotification(Account recipient) {
        transactionTemplate.executeWithoutResult(s ->
                eventPublisher.publishEvent(ReservationCreatedEvent.builder()
                        .instructorAccountId(recipient.getId())
                        .studentAccountId(recipient.getId())
                        .lectureId(100L)
                        .scheduleId(200L)
                        .studentNickname("학생")
                        .lectureTitle("프리다이빙 입문")
                        .build()));
    }

    private UserNotification persistNotification(Account recipient, String suffix, boolean read) {
        return userNotificationRepo.save(UserNotification.builder()
                .notificationId("uuid-" + suffix)
                .recipientAccountId(recipient.getId())
                .type(NotificationType.RESERVATION_CREATED)
                .title("제목-" + suffix)
                .body("본문-" + suffix)
                .data("{\"type\":\"RESERVATION_CREATED\",\"lectureId\":\"1\"}")
                // 나노초(9자리)를 일부러 심는다 — 리눅스 CI 의 OffsetDateTime.now() 정밀도를 재현해
                // "인메모리 값 vs DB 왕복 값" 비교 버그가 macOS 에서도 잡히게 한다(CI 에서만 깨지던 회귀).
                .readAt(read ? OffsetDateTime.now(ZoneOffset.UTC).withNano(830_211_255) : null)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    @Test
    @DisplayName("S1 알림이 발행되면 outbox 행과 알림함 행이 같은 notificationId 로 함께 생성된다")
    void publishing_writesBothOutboxAndInbox() {
        Account recipient = persistAccount("recipient@test.com");

        publishOneNotification(recipient);

        assertThat(outboxRepo.findAll()).hasSize(1);
        List<UserNotification> inbox = userNotificationRepo.findAll();
        assertThat(inbox).hasSize(1);
        UserNotification row = inbox.get(0);
        assertThat(row.getRecipientAccountId()).isEqualTo(recipient.getId());
        assertThat(row.getReadAt()).isNull();
        // 푸시 payload 의 data.notificationId 와 같은 값이어야 푸시↔알림함 상관이 성립한다.
        assertThat(outboxRepo.findAll().get(0).getPayload()).contains(row.getNotificationId());
        // data 맵은 라우팅에 쓰이므로 type 이 반드시 들어 있어야 한다.
        assertThat(row.getData()).contains("\"type\":\"RESERVATION_CREATED\"");
    }

    @Test
    @DisplayName("S2 내 알림 목록이 최신순으로 오고 _embedded.notifications 키로 감싸진다")
    void feed_returnsHalPagedModel() throws Exception {
        Account me = persistAccount("me@test.com");
        persistNotification(me, "a", false);
        persistNotification(me, "b", false);

        mockMvc.perform(get("/me/notifications?page=0&size=20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                // FE 가 unwrapHalPage(body, 'notifications') 로 읽는 경로 — @Relation 이 빠지면 여기서 깨진다.
                .andExpect(jsonPath("$._embedded.notifications").isArray())
                .andExpect(jsonPath("$._embedded.notifications.length()").value(2))
                // PagedModel 이어야 page 블록이 온다(CollectionModel 이면 없음 → FE 무한스크롤 종료 판정 깨짐)
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.number").value(0));
    }

    @Test
    @DisplayName("S3 unreadOnly=true 면 미읽음만 오고 totalElements 도 미읽음 총계다")
    void feed_unreadOnlyFiltersOnServer() throws Exception {
        Account me = persistAccount("me@test.com");
        persistNotification(me, "unread", false);
        persistNotification(me, "read", true);

        mockMvc.perform(get("/me/notifications?unreadOnly=true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.notifications.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("S4 미읽음 카운트가 읽음처리 후 줄어든다")
    void unreadCount_decreasesAfterMarkRead() throws Exception {
        Account me = persistAccount("me@test.com");
        UserNotification n = persistNotification(me, "a", false);

        mockMvc.perform(get("/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(patch("/me/notifications/{id}/read", n.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("S5 읽음처리는 멱등이다 — 두 번 호출해도 readAt 이 최초 값을 유지한다")
    void markRead_isIdempotent() throws Exception {
        Account me = persistAccount("me@test.com");
        UserNotification n = persistNotification(me, "a", false);

        mockMvc.perform(patch("/me/notifications/{id}/read", n.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isNoContent());
        OffsetDateTime first = userNotificationRepo.findById(n.getId()).orElseThrow().getReadAt();

        mockMvc.perform(patch("/me/notifications/{id}/read", n.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isNoContent());

        assertThat(userNotificationRepo.findById(n.getId()).orElseThrow().getReadAt())
                .isEqualTo(first);
    }

    @Test
    @DisplayName("S6 전체 읽음처리는 미읽음만 갱신하고 이미 읽은 행의 시각은 건드리지 않는다")
    void markAllRead_touchesOnlyUnread() throws Exception {
        Account me = persistAccount("me@test.com");
        UserNotification alreadyRead = persistNotification(me, "read", true);
        UserNotification unread = persistNotification(me, "unread", false);
        // ⚠️ 기준값은 <b>DB 에서 다시 읽는다</b> — 인메모리 값과 비교하면 안 된다.
        // OffsetDateTime.now() 정밀도가 플랫폼마다 다르고(리눅스 나노초 / macOS 마이크로초) DB 왕복에서
        // 잘리므로, 인메모리 값(9자리)과 DB 값(6자리)을 비교하면 리눅스에서만 깨진다.
        // 실제로 로컬은 통과하고 CI 만 실패했던 원인이 이것이다.
        OffsetDateTime originalReadAt = userNotificationRepo.findById(alreadyRead.getId())
                .orElseThrow().getReadAt();

        mockMvc.perform(patch("/me/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isNoContent());

        assertThat(userNotificationRepo.findById(unread.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(userNotificationRepo.findById(alreadyRead.getId()).orElseThrow().getReadAt())
                .isEqualTo(originalReadAt);
        assertThat(userNotificationRepo.countByRecipientAccountIdAndReadAtIsNull(me.getId())).isZero();
    }

    @Test
    @DisplayName("R1 남의 알림 목록은 보이지 않는다 — 내 것만 조회된다")
    void feed_returnsOnlyMine() throws Exception {
        Account me = persistAccount("me@test.com");
        Account other = persistAccount("other@test.com");
        persistNotification(me, "mine", false);
        persistNotification(other, "theirs", false);

        mockMvc.perform(get("/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.notifications.length()").value(1))
                .andExpect(jsonPath("$._embedded.notifications[0].title").value("제목-mine"));
    }

    /**
     * 남의 알림은 <b>존재를 숨긴다</b> — 권한 오류(403)가 아니라 "그런 리소스 없음" 으로 답한다.
     * 403 이면 "그 id 는 존재한다" 를 알려주는 오라클이 되어 열거 공격의 실마리가 된다.
     *
     * <p>상태코드가 400 인 것은 이 레포의 기존 규약이다 — {@code ResourceNotFoundException} 이
     * {@code ExceptionAdvice:78-82} 에서 {@code BAD_REQUEST} + 전용 에러코드로 매핑된다
     * (enrollment 의 {@code requireForInstructor} 등이 이미 같은 방식). 알림함만 404 를 쓰면
     * 클라이언트 에러 처리가 갈라지므로 레포 규약을 따른다.
     */
    @Test
    @DisplayName("R2 남의 알림 id 로 읽음처리를 시도하면 거부되고 그 알림은 읽음 처리되지 않는다 (존재 숨김)")
    void markRead_othersNotification_isRejected() throws Exception {
        Account me = persistAccount("me@test.com");
        Account other = persistAccount("other@test.com");
        UserNotification theirs = persistNotification(other, "theirs", false);

        mockMvc.perform(patch("/me/notifications/{id}/read", theirs.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(userNotificationRepo.findById(theirs.getId()).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("R3 비로그인 조회는 401")
    void feed_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/me/notifications"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 빈 수신함은 HAL 규약상 {@code _embedded} 키 자체가 <b>빠진다</b>(원소가 없으므로).
     * FE 가 그걸 {@code []} 로 방어하고 있어(크로스체크 확인) 계약 위반이 아니지만, 여기서 고정해 둔다 —
     * 나중에 누가 빈 배열을 강제로 넣으면 그때 이 테스트가 "계약이 바뀌었다"고 알려준다.
     */
    @Test
    @DisplayName("S7 알림이 하나도 없으면 빈 페이지가 오고 totalElements 는 0 이다")
    void feed_emptyInbox() throws Exception {
        Account me = persistAccount("me@test.com");

        mockMvc.perform(get("/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$._embedded").doesNotExist());

        mockMvc.perform(get("/me/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0)); // 0 건도 200 이다(4xx 아님)
    }

    @Test
    @DisplayName("V1 size 상한(50)을 넘겨 요청해도 50으로 잘린다")
    void feed_capsPageSize() throws Exception {
        Account me = persistAccount("me@test.com");
        persistNotification(me, "a", false);

        mockMvc.perform(get("/me/notifications?size=100000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    @Test
    @DisplayName("X1 비즈니스 트랜잭션이 롤백되면 알림함 행도 함께 사라진다 (유령 알림 방지)")
    void inboxRow_rollsBackWithBusinessTransaction() {
        Account recipient = persistAccount("recipient@test.com");

        try {
            transactionTemplate.executeWithoutResult(s -> {
                eventPublisher.publishEvent(ReservationCreatedEvent.builder()
                        .instructorAccountId(recipient.getId())
                        .studentAccountId(recipient.getId())
                        .lectureId(100L)
                        .scheduleId(200L)
                        .studentNickname("학생")
                        .lectureTitle("롤백될 강의")
                        .build());
                throw new IllegalStateException("비즈니스 실패 — 롤백");
            });
        } catch (IllegalStateException expected) {
            // 의도된 롤백
        }

        // MANDATORY 전파라 리스너가 publisher 트랜잭션에 합류한다 → 둘 다 사라져야 한다.
        assertThat(userNotificationRepo.findAll()).isEmpty();
        assertThat(outboxRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("X2 알림함 행은 outbox 발송 결과와 무관하게 남는다 (토큰 없어 GAVE_UP 이어도 수신함엔 보임)")
    void inboxRow_survivesDeliveryFailure() {
        Account recipient = persistAccount("recipient@test.com"); // 디바이스 토큰 없음

        publishOneNotification(recipient);
        Long outboxId = outboxRepo.findAll().get(0).getId();
        transactionTemplate.executeWithoutResult(s ->
                outboxRepo.findById(outboxId).orElseThrow().markGaveUp("no tokens"));

        assertThat(outboxRepo.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.GAVE_UP);
        // 푸시는 못 갔지만 알림함엔 남아 있어야 한다 — 이게 별도 테이블을 만든 이유다.
        assertThat(userNotificationRepo.findAll()).hasSize(1);
    }
}
