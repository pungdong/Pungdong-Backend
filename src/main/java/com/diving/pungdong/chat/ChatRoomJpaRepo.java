package com.diving.pungdong.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatRoomJpaRepo extends JpaRepository<ChatRoom, Long> {

    /** 회차 카드 목록용 — 방 id(=세션 id)를 모아 한 번에. N+1 회피. */
    List<ChatRoom> findByIdIn(Collection<Long> ids);
}
