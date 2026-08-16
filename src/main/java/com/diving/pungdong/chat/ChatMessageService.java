package com.diving.pungdong.chat;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.chat.dto.ChatMessageListResponse;
import com.diving.pungdong.chat.dto.ChatMessageResponse;
import com.diving.pungdong.chat.dto.ChatSendRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.TooManyRequestsException;
import com.diving.pungdong.notification.event.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 메시지 목록(커서)·전송(멱등)·읽음 처리.
 *
 * <p><b>커서 페이지네이션은 이 레포 관례로부터의 의도적 이탈이다</b>(다른 목록은 전부 {@code Pageable} +
 * {@code PagedResourcesAssembler} → {@code PagedModel}). 채팅은 append-heavy 라 새 메시지가 들어오면
 * offset 페이지가 밀려 과거 스크롤에서 중복·누락이 난다. 근거는 패키지 CLAUDE.md 에도 적어 뒀다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /** 기본/최대 페이지 크기. 커뮤니티(20/50)보다 큰 건 채팅 1화면 밀도가 높아서다. */
    static final int DEFAULT_SIZE = 30;
    static final int MAX_SIZE = 100;

    /** 레이트리밋 — 최근 {@value #RATE_WINDOW_SECONDS}초에 {@value #RATE_LIMIT} 건 이상이면 거부. */
    static final int RATE_WINDOW_SECONDS = 10;
    static final int RATE_LIMIT = 10;

    /** 삭제된 메시지 자리 표시(v1 은 삭제 API 가 없어 실제로는 안 쓰인다 — 렌더 규칙만 고정). */
    static final String DELETED_TEXT = "삭제된 메시지입니다.";

    /** 푸시 본문에 싣는 원문 길이. */
    private static final int PUSH_BODY_MAX = 40;

    private final ChatRoomService roomService;
    private final ChatRoomJpaRepo roomRepo;
    private final ChatMessageJpaRepo messageRepo;
    private final ChatParticipantJpaRepo participantRepo;
    private final ChatReadStateJpaRepo readStateRepo;
    private final com.diving.pungdong.availability.AvailabilitySessionJpaRepo sessionRepo;
    private final ApplicationEventPublisher events;

    /* ─── 목록 ───────────────────────────────────────────────── */

    /**
     * 커서 목록. {@code before}(과거)·{@code after}(폴링) 중 하나만, 없으면 최신 N건.
     *
     * <p>응답은 <b>항상 id 오름차순</b>이라 FE 가 재정렬하지 않는다. {@code before} 는 반드시
     * <b>커서에 가장 가까운 과거</b> N건이어야 한다 — "가장 오래된 N건" 을 주면 위로 스크롤할 때마다
     * 대화 맨 처음으로 점프한다.
     */
    @Transactional
    public ChatMessageListResponse list(Account viewer, Long roomId, Long before, Long after, Integer size) {
        if (before != null && after != null) {
            throw new BadRequestException("before 와 after 는 함께 쓸 수 없어요.");
        }
        ChatRoom room = roomService.requireAccessibleRoom(viewer, roomId);
        int limit = normalizeSize(size);
        Pageable page = PageRequest.of(0, limit);

        List<ChatMessage> rows;
        boolean forward = after != null;
        if (forward) {
            rows = messageRepo.findAfter(room.getId(), after, page);
        } else if (before != null) {
            rows = new ArrayList<>(messageRepo.findBefore(room.getId(), before, page));
            Collections.reverse(rows);   // DESC 로 뽑아 인접 과거를 잡고, 응답은 ASC 로 되돌린다
        } else {
            rows = new ArrayList<>(messageRepo.findLatest(room.getId(), page));
            Collections.reverse(rows);
        }

        boolean hasMore = false;
        Long nextCursor;
        if (rows.isEmpty()) {
            // ⚠️ 빈 목록이면 요청 커서를 그대로 에코한다. null 을 주면 호출부가 무심코
            // cursor = res.nextCursor 했을 때 커서가 날아가고, 다음 폴링이 최신 N건을 통째로 다시 가져와
            // 중복 렌더가 난다(after 폴링은 대부분 빈 목록이다).
            nextCursor = forward ? after : before;
        } else if (forward) {
            nextCursor = rows.get(rows.size() - 1).getId();
            hasMore = messageRepo.existsByRoomIdAndIdGreaterThan(room.getId(), nextCursor);
        } else {
            nextCursor = rows.get(0).getId();
            hasMore = messageRepo.existsByRoomIdAndIdLessThan(room.getId(), nextCursor);
        }

        return ChatMessageListResponse.builder()
                .messages(toResponses(room.getId(), rows, viewer.getId()))
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /* ─── 전송 ───────────────────────────────────────────────── */

    /**
     * 전송. 같은 {@code clientMessageId} 로 다시 오면 <b>새로 만들지 않고 기존 메시지를 돌려준다</b>
     * (에러 아님 — 컨트롤러가 200 으로 내린다).
     *
     * @return 새로 저장했으면 {@code true} + 메시지(→ 201), 중복이면 {@code false} + 기존 메시지(→ 200)
     */
    @Transactional
    public Sent send(Account sender, Long roomId, ChatSendRequest request) {
        ChatRoom room = roomService.requireAccessibleRoom(sender, roomId);
        boolean sessionAlive = sessionRepo.existsById(room.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (ChatRoomService.stateOf(room, sessionAlive, now) == ChatRoomState.CLOSED) {
            throw new BadRequestException("종료된 회차 채팅방에는 메시지를 보낼 수 없어요.");
        }

        // 재전송이 먼저다 — 레이트리밋보다 앞에 둬야 "이미 저장된 걸 다시 확인하는 요청" 이 429 로 막히지 않는다.
        Optional<ChatMessage> duplicate =
                messageRepo.findBySenderAccountIdAndClientMessageId(sender.getId(), request.getClientMessageId());
        if (duplicate.isPresent()) {
            return new Sent(toResponse(room.getId(), duplicate.get(), sender.getId()), false);
        }

        requireUnderRateLimit(sender.getId(), now);

        ChatMessage message;
        try {
            message = messageRepo.saveAndFlush(ChatMessage.builder()
                    .roomId(room.getId())
                    .senderAccountId(sender.getId())
                    .clientMessageId(request.getClientMessageId())
                    .kind(ChatMessageKind.USER)
                    .text(request.getText())
                    .deleted(false)
                    .createdAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 전송이 먼저 커밋됐다 — 중복과 같게 취급한다.
            return new Sent(messageRepo
                    .findBySenderAccountIdAndClientMessageId(sender.getId(), request.getClientMessageId())
                    .map(m -> toResponse(room.getId(), m, sender.getId()))
                    .orElseThrow(() -> e), false);
        }

        publishPush(room, message, sender);
        return new Sent(toResponse(room.getId(), message, sender.getId()), true);
    }

    /**
     * 참여자 전원(발신자 제외)에게 푸시. 수신자 목록을 이벤트가 통째로 싣고 리스너가 수신자당 enqueue 하는
     * 기존 fan-out 패턴({@code NotificationOutboxWriter.onLectureNotification})을 따른다.
     *
     * <p>수신자가 없으면 <b>발행하지 않는다</b> — 알림 실패가 메시지 저장을 깨지 않게 하는 기존 가드
     * ({@code canNotifyStudent()})와 같은 취지다. 중복 전송(멱등 히트)은 이 경로를 타지 않으므로
     * 재시도 한 번에 참여자 전원이 알림을 두 번 받는 일이 없다.
     */
    private void publishPush(ChatRoom room, ChatMessage message, Account sender) {
        List<Long> recipients = participantRepo.findByRoomIdAndLeftAtIsNull(room.getId()).stream()
                .map(ChatParticipant::getAccountId)
                .filter(id -> !id.equals(sender.getId()))
                .collect(Collectors.toList());
        if (recipients.isEmpty()) {
            return;
        }
        events.publishEvent(ChatMessageEvent.builder()
                .recipientAccountIds(recipients)
                .roomId(room.getId())
                .messageId(message.getId())
                .courseTitle(room.getCourseTitle())
                .roundIndex(room.getRoundIndex())
                .senderNickName(sender.getNickName())
                .preview(preview(message.getText()))
                .build());
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= PUSH_BODY_MAX ? flat : flat.substring(0, PUSH_BODY_MAX) + "…";
    }

    /**
     * 최근 창 안의 전송 건수로 막는다. 이 레포엔 범용 레이트리밋 인프라가 없고(유일 선례는 본인확인 OTP 의
     * Redis 쿨다운), 대형 인프라를 짓지 않기로 한 판정에 맞춘 최소 가드다. 인덱스
     * {@code (sender_account_id, created_at)} 로 센다.
     */
    private void requireUnderRateLimit(Long senderId, OffsetDateTime now) {
        OffsetDateTime windowStart = now.minusSeconds(RATE_WINDOW_SECONDS);
        if (messageRepo.countBySenderAccountIdAndCreatedAtAfter(senderId, windowStart) < RATE_LIMIT) {
            return;
        }
        long retryAfter = messageRepo
                .findFirstBySenderAccountIdAndCreatedAtAfterOrderByCreatedAtAsc(senderId, windowStart)
                .map(oldest -> Duration.between(now, oldest.getCreatedAt().plusSeconds(RATE_WINDOW_SECONDS))
                        .getSeconds() + 1)
                .orElse((long) RATE_WINDOW_SECONDS);
        throw new TooManyRequestsException(Math.max(1, retryAfter));
    }

    /* ─── 읽음 ───────────────────────────────────────────────── */

    /** 멱등 — 전진만 한다. 폴링과 경합해도 되감기지 않는다. */
    @Transactional
    public void markRead(Account viewer, Long roomId, Long lastReadMessageId) {
        ChatRoom room = roomService.requireAccessibleRoom(viewer, roomId);
        ChatReadState state = readStateRepo.findByRoomIdAndAccountId(room.getId(), viewer.getId())
                .orElseGet(() -> ChatReadState.builder()
                        .roomId(room.getId()).accountId(viewer.getId()).lastReadMessageId(0L).build());
        if (state.advanceTo(lastReadMessageId) || state.getId() == null) {
            readStateRepo.save(state);
        }
    }

    /* ─── 매핑 ───────────────────────────────────────────────── */

    /**
     * 발신자 표시는 참여자 이력에서 해석한다 — <b>이탈자({@code leftAt} 있음)도 포함</b>해야 그 사람이
     * 과거에 남긴 말풍선의 이름이 빈칸이 되지 않는다. (참여자 목록·카운트는 반대로 현재 참여자만 센다.)
     * 방 단위로 한 번 로드해 맵으로 매핑한다 — 메시지마다 조회하면 N+1 이다.
     */
    private List<ChatMessageResponse> toResponses(Long roomId, List<ChatMessage> rows, Long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        SenderIndex index = senderIndex(roomId);
        return rows.stream().map(m -> toResponse(index, m, viewerId)).collect(Collectors.toList());
    }

    private ChatMessageResponse toResponse(Long roomId, ChatMessage message, Long viewerId) {
        return toResponse(senderIndex(roomId), message, viewerId);
    }

    private ChatMessageResponse toResponse(SenderIndex index, ChatMessage m, Long viewerId) {
        ChatParticipantRole role = m.getSenderAccountId() == null ? null : index.roles.get(m.getSenderAccountId());
        String nickName = m.getSenderAccountId() == null ? null : index.nickNames.get(m.getSenderAccountId());
        return ChatMessageResponse.builder()
                .id(m.getId())
                .kind(m.getKind())
                .text(m.isDeleted() ? DELETED_TEXT : m.getText())
                .deleted(m.isDeleted())
                .sentAt(m.getCreatedAt())
                .senderId(m.getSenderAccountId())
                .senderDisplayName(role == null ? null : role.displayName(nickName))
                .senderName(nickName)
                .senderRole(role)
                .mine(m.getSenderAccountId() != null && m.getSenderAccountId().equals(viewerId))
                .clientMessageId(m.getClientMessageId())
                .build();
    }

    private SenderIndex senderIndex(Long roomId) {
        List<ChatParticipant> all = participantRepo.findByRoomId(roomId);
        Map<Long, ChatParticipantRole> roles = all.stream().collect(Collectors.toMap(
                ChatParticipant::getAccountId, ChatParticipant::getRole, (a, b) -> a));
        Map<Long, String> nickNames = roomService.nickNamesOf(new ArrayList<>(roles.keySet()));
        return new SenderIndex(roles, nickNames);
    }

    private static final class SenderIndex {
        private final Map<Long, ChatParticipantRole> roles;
        private final Map<Long, String> nickNames;

        private SenderIndex(Map<Long, ChatParticipantRole> roles, Map<Long, String> nickNames) {
            this.roles = roles;
            this.nickNames = nickNames;
        }
    }

    /** 전송 결과 — {@code created} 가 false 면 멱등 히트(기존 메시지)라 컨트롤러가 200 으로 내린다. */
    public static final class Sent {
        private final ChatMessageResponse message;
        private final boolean created;

        Sent(ChatMessageResponse message, boolean created) {
            this.message = message;
            this.created = created;
        }

        public ChatMessageResponse getMessage() {
            return message;
        }

        public boolean isCreated() {
            return created;
        }
    }
}
