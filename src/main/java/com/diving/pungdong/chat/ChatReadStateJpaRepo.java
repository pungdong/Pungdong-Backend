package com.diving.pungdong.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatReadStateJpaRepo extends JpaRepository<ChatReadState, Long> {

    Optional<ChatReadState> findByRoomIdAndAccountId(Long roomId, Long accountId);
}
