package com.diving.pungdong.notification;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.notification.dto.UnreadCountResponse;
import com.diving.pungdong.notification.dto.UserNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인앱 알림함. 신분은 세션({@code @CurrentUser})에서만 — accountId 를 파라미터로 받지 않는다.
 *
 * <p>시큐리티 매처를 따로 추가하지 않는다 — {@code SecurityConfiguration} 의
 * {@code anyRequest().authenticated()} 가 이미 덮는다(기존 {@code /me/devices} 와 동일 방식).
 */
@RestController
@RequestMapping("/me/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService userNotificationService;

    /**
     * 알림 목록. {@code page} 는 0-based, {@code size} 기본 20·상한 50, 정렬은 서버 고정(createdAt DESC).
     *
     * <p>⚠️ 반드시 {@code PagedResourcesAssembler} 로 <b>PagedModel</b> 을 반환한다 —
     * {@code CollectionModel} 이면 {@code page} 블록이 빠져 FE 의 무한스크롤 종료 판정이 깨진다
     * (커뮤니티 댓글 목록에서 실제로 발생한 사고).
     */
    @GetMapping
    public ResponseEntity<?> feed(@CurrentUser Account account,
                                  @RequestParam(defaultValue = "false") boolean unreadOnly,
                                  Pageable pageable,
                                  PagedResourcesAssembler<UserNotificationResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(
                userNotificationService.feed(account.getId(), unreadOnly, pageable)));
    }

    /** 미읽음 개수 — 뱃지용. 0 건도 200 이다. */
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@CurrentUser Account account) {
        return ResponseEntity.ok(new UnreadCountResponse(
                userNotificationService.unreadCount(account.getId())));
    }

    /** 단건 읽음. 남의 알림이면 404(존재 숨김). 멱등 — 이미 읽었어도 204. */
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@CurrentUser Account account, @PathVariable Long id) {
        userNotificationService.markRead(account.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 전체 읽음. 벌크 UPDATE 한 방 — 미읽음만 갱신한다. */
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@CurrentUser Account account) {
        userNotificationService.markAllRead(account.getId());
        return ResponseEntity.noContent().build();
    }
}
