package com.diving.pungdong.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageJpaRepo extends JpaRepository<ChatMessage, Long> {

    /**
     * 과거 방향(backward) — <b>커서에 가장 가까운</b> N건. {@code before} 는 exclusive.
     *
     * <p>⚠️ {@code ORDER BY id DESC} 가 핵심이다. ASC 로 뽑으면 "가장 오래된 N건" 이 나와서 위로 스크롤할
     * 때마다 대화 맨 처음으로 점프한다. 호출부가 결과를 뒤집어 ASC 로 응답한다.
     */
    @Query("select m from ChatMessage m where m.roomId = :roomId and m.id < :before order by m.id desc")
    List<ChatMessage> findBefore(@Param("roomId") Long roomId, @Param("before") Long before, Pageable pageable);

    /** 최초 진입(커서 없음) — 최신 N건. backward 로 취급한다. 호출부가 뒤집는다. */
    @Query("select m from ChatMessage m where m.roomId = :roomId order by m.id desc")
    List<ChatMessage> findLatest(@Param("roomId") Long roomId, Pageable pageable);

    /** 최신 방향(forward, 폴링) — {@code after} exclusive, 오래된 것부터. 인덱스 (room_id, id) range scan. */
    @Query("select m from ChatMessage m where m.roomId = :roomId and m.id > :after order by m.id asc")
    List<ChatMessage> findAfter(@Param("roomId") Long roomId, @Param("after") Long after, Pageable pageable);

    /** hasMore(backward) — 더 과거가 있는가. */
    boolean existsByRoomIdAndIdLessThan(Long roomId, Long id);

    /** hasMore(forward) — 더 최신이 있는가(= 버스트가 size 를 넘었는가). */
    boolean existsByRoomIdAndIdGreaterThan(Long roomId, Long id);

    /** 폴링 초기 커서. */
    Optional<ChatMessage> findFirstByRoomIdOrderByIdDesc(Long roomId);

    /** 전송 멱등 — UNIQUE 충돌 시 기존 메시지를 되돌려주기 위해. */
    Optional<ChatMessage> findBySenderAccountIdAndClientMessageId(Long senderAccountId, String clientMessageId);

    /** 레이트리밋 — 인덱스 (sender_account_id, created_at). */
    long countBySenderAccountIdAndCreatedAtAfter(Long senderAccountId, OffsetDateTime after);

    /** 레이트리밋 초과 시 "몇 초 뒤" 계산용 — 창 안에서 가장 오래된 건. */
    Optional<ChatMessage> findFirstBySenderAccountIdAndCreatedAtAfterOrderByCreatedAtAsc(
            Long senderAccountId, OffsetDateTime after);

    /**
     * 방별 unread 를 <b>쿼리 1방</b>으로 — 회차 카드가 N개면 방마다 세는 순간 N+1 이다.
     *
     * <p>읽음상태 행이 없는 방은 {@code coalesce(..., 0)} 으로 "하나도 안 읽음" 이 된다. 내가 보낸 건
     * 세지 않고, SYSTEM(발신자 null)은 센다.
     *
     * @return {@code [roomId, count]} 행들
     */
    @Query("select m.roomId, count(m) from ChatMessage m "
            + "where m.roomId in :roomIds and m.deleted = false "
            + "and (m.senderAccountId is null or m.senderAccountId <> :accountId) "
            + "and m.id > coalesce((select r.lastReadMessageId from ChatReadState r "
            + "where r.roomId = m.roomId and r.accountId = :accountId), 0L) "
            + "group by m.roomId")
    List<Object[]> countUnreadByRoom(@Param("accountId") Long accountId,
                                     @Param("roomIds") Collection<Long> roomIds);
}
