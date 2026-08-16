package com.diving.pungdong.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatParticipantJpaRepo extends JpaRepository<ChatParticipant, Long> {

    /** 이탈자 포함 — reconcile 이 재합류(leftAt 되돌리기)를 하려면 죽은 행도 찾아야 한다. */
    Optional<ChatParticipant> findByRoomIdAndAccountId(Long roomId, Long accountId);

    /** 방의 모든 참여자 이력(이탈자 포함) — reconcile 과 <b>발신자 이름 매핑</b>이 쓴다. */
    List<ChatParticipant> findByRoomId(Long roomId);

    /** 현재 참여자만 — 권한 판정·참여자 목록·푸시 수신자. 헤더 "참여자 3명" 은 이쪽이다. */
    List<ChatParticipant> findByRoomIdAndLeftAtIsNull(Long roomId);

    /**
     * 여러 방에서 <b>내가 현재 참여자인 방</b>만 골라낸다 — 회차 카드 N건의 HIDDEN 판정을 쿼리 1방으로.
     * 방마다 조회하면 N+1 이다.
     */
    @Query("select p.roomId from ChatParticipant p "
            + "where p.accountId = :accountId and p.roomId in :roomIds and p.leftAt is null")
    List<Long> findActiveRoomIds(@Param("accountId") Long accountId,
                                 @Param("roomIds") Collection<Long> roomIds);
}
