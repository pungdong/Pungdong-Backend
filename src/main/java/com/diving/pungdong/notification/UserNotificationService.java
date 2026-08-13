package com.diving.pungdong.notification;

import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.notification.dto.UserNotificationResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserNotificationJpaRepo repo;
    private final ObjectMapper objectMapper;

    /**
     * 내 알림 목록. 정렬은 서버 고정(createdAt DESC).
     *
     * <p>{@code unreadOnly} 는 <b>서버 필터</b>다 — 클라이언트에서 거르면 페이지네이션이 깨진다
     * (20건 받아 3건만 그리면 화면은 3줄인데 "더 보기" 는 남고, 미읽음이 뒤에 몰려 있으면 여러 페이지를
     * 긁어야 한다). 서버가 거르면 {@code totalElements} 가 그 탭의 진짜 총계가 되어 탭 라벨에도 쓴다.
     */
    @Transactional(readOnly = true)
    public Page<UserNotificationResponse> feed(Long accountId, boolean unreadOnly, Pageable pageable) {
        Pageable fixed = NotificationPaging.fixed(pageable);
        Page<UserNotification> page = unreadOnly
                ? repo.findByRecipientAccountIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(accountId, fixed)
                : repo.findByRecipientAccountIdOrderByCreatedAtDescIdDesc(accountId, fixed);
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long accountId) {
        return repo.countByRecipientAccountIdAndReadAtIsNull(accountId);
    }

    /**
     * 소유권 검증을 쿼리에 내장한다 — 남의 알림은 조회 자체가 안 되므로 IDOR 이 구조적으로 막힌다.
     * 이미 읽었으면 no-op(멱등).
     *
     * <p>남의 알림이면 {@code ResourceNotFoundException} → <b>400 + 존재 숨김</b>
     * ({@code ExceptionAdvice:78-79} 가 {@code BAD_REQUEST} 로 매핑. 404 아님).
     */
    @Transactional
    public void markRead(Long accountId, Long notificationRowId) {
        UserNotification notification = repo.findByIdAndRecipientAccountId(notificationRowId, accountId)
                .orElseThrow(ResourceNotFoundException::new);
        notification.markRead();
    }

    @Transactional
    public void markAllRead(Long accountId) {
        repo.markAllRead(accountId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private UserNotificationResponse toResponse(UserNotification n) {
        return UserNotificationResponse.builder()
                .id(n.getId())
                .notificationId(n.getNotificationId())
                .type(n.getType())
                .title(n.getTitle())
                .body(n.getBody())
                .data(deserializeData(n))
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }

    /**
     * {@code data} 는 표시·라우팅용 부가정보다. 역직렬화가 깨져도 <b>목록 전체를 500 으로 만들지 않는다</b>
     * — 제목·본문은 멀쩡하므로 그 줄을 보여주는 편이 낫다. (발송 워커가 payload 역직렬화 실패 시 던지는
     * 것과 의도적으로 다르다 — 거기는 발송이 목적이라 실패해야 재시도된다.)
     */
    private Map<String, String> deserializeData(UserNotification n) {
        if (n.getData() == null || n.getData().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(n.getData(), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("UserNotification {} data 역직렬화 실패 — data 없이 반환", n.getId(), e);
            return null;
        }
    }
}
