package com.diving.pungdong.chat.dto;

import com.diving.pungdong.chat.ChatRoomState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 방 상세 — {@code GET /chat/rooms/{roomId}} (없으면 생성).
 *
 * <p>헤더 정보는 전부 <b>방 스냅샷</b>이다(슬롯 라이브 조회 아님). 세션이 물리 삭제돼도 헤더가 깨지지
 * 않아야 하기 때문. 세션이 살아 있으면 조회 때 스냅샷과 {@code closesAt} 을 갱신한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomResponse {

    private Long roomId;

    /** {@code ACTIVE} | {@code CLOSED}. {@code HIDDEN} 은 여기 안 나온다 — 비참여자는 -1009 다. */
    private ChatRoomState state;

    /**
     * 마감까지 남은 <b>초</b> — {@code ACTIVE} 일 때만, {@code CLOSED} 면 null.
     *
     * <p>절대시각({@code closesAt})을 안 주는 이유: 기기 시계가 서버와 어긋나면 카운트다운이 그만큼 밀린다.
     * {@code otpExpiresInSeconds}/{@code paymentExpiresInSeconds} 와 같은 규칙이다. 슬롯 시간이 바뀌면
     * 이 값이 <b>늘어날 수도</b> 있으므로 FE 는 매 조회마다 타이머를 재설정한다(로컬 감산 누적 금지).
     */
    private Long closesInSeconds;

    private String courseTitle;
    private Integer roundIndex;

    /** civil — 오프셋 없음. FE 가 {@code new Date()} 로 만지면 안 된다. */
    private LocalDate date;
    /** civil, {@code HH:mm:ss}. 기존 {@code AvailabilitySessionResponse.startTime} 과 같은 포맷. */
    private LocalTime startTime;
    private LocalTime endTime;

    private String venueName;

    /** 현재 참여자 수(이탈자 제외) — 헤더 "참여자 3명". */
    private int participantCount;
    /** 현재 참여자만. 이탈자는 발신자 이름 해석에만 쓰이고 여기 안 들어간다. */
    private List<ChatParticipantResponse> participants;

    private int unreadCount;

    /** 폴링 초기 커서 — FE 가 이 값으로 {@code ?after=} 를 시작한다. 메시지가 없으면 null. */
    private Long latestMessageId;
}
