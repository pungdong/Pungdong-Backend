package com.diving.pungdong.chat;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.chat.dto.ChatReadRequest;
import com.diving.pungdong.chat.dto.ChatSendRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 세션 단체 채팅 — 전 엔드포인트 인증 + 참여자 검사.
 *
 * <p>{@code roomId} 는 <b>FE 가 만들지 않는다</b>. 회차 카드·슬롯 상세 응답의 {@code chat.roomId} 나
 * 푸시 {@code data.roomId} 로 받은 값을 그대로 되돌려준다. 오늘 이 값이 세션 id 와 같은 숫자인 것은
 * BE 내부 구현이고, FE 가 {@code session.id} 로 구성하면 나중에 방 키를 분리할 때 깨진다.
 *
 * <p>비참여자·없는 방은 {@code ResourceNotFoundException}(존재 숨김)이고, 이 레포에서 그건
 * <b>HTTP 400 + code -1009</b> 다(404 아님). FE 딥링크 폴백은 status 가 아니라 code 로 건다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {

    private final ChatRoomService roomService;
    private final ChatMessageService messageService;

    /** 방 상세 — 없으면 생성(지연 생성). CLOSED 방도 200 이다(읽기 전용이라 대화는 보여야 한다). */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<?> room(@CurrentUser Account account, @PathVariable Long roomId) {
        return ResponseEntity.ok().body(roomService.open(account, roomId));
    }

    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<?> participants(@CurrentUser Account account, @PathVariable Long roomId) {
        return ResponseEntity.ok().body(roomService.participants(account, roomId));
    }

    /**
     * 커서 목록. {@code before}(과거 스크롤)·{@code after}(폴링) 중 하나만.
     * 응답은 방향과 무관하게 항상 id 오름차순이다.
     */
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<?> messages(@CurrentUser Account account,
                                      @PathVariable Long roomId,
                                      @RequestParam(required = false) Long before,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().body(messageService.list(account, roomId, before, after, size));
    }

    /**
     * 전송. 같은 {@code clientMessageId} 로 다시 오면 <b>200 + 기존 메시지</b>(에러 아님), 신규면 201.
     * FE 는 둘을 같게 처리한다 — 어느 쪽이든 낙관적 말풍선을 확정으로 바꾼다.
     */
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<?> send(@CurrentUser Account account,
                                  @PathVariable Long roomId,
                                  @Valid @RequestBody ChatSendRequest request,
                                  BindingResult result) {
        reject(result);
        ChatMessageService.Sent sent = messageService.send(account, roomId, request);
        return sent.isCreated()
                ? ResponseEntity.status(201).body(sent.getMessage())
                : ResponseEntity.ok().body(sent.getMessage());
    }

    /** 읽음 처리 — 멱등(전진만). */
    @PatchMapping("/rooms/{roomId}/read")
    public ResponseEntity<?> read(@CurrentUser Account account,
                                  @PathVariable Long roomId,
                                  @Valid @RequestBody ChatReadRequest request,
                                  BindingResult result) {
        reject(result);
        messageService.markRead(account, roomId, request.getLastReadMessageId());
        return ResponseEntity.noContent().build();
    }

    private void reject(BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
    }
}
