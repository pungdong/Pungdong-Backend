package com.diving.pungdong.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueJpaRepo extends JpaRepository<Venue, Long> {

    /** 한 강사가 만든 커스텀 위치 목록 — 코스 빌더에서 본인 것만 본다(남의 커스텀은 비공개). */
    List<Venue> findAllByOwnerIdOrderByIdDesc(Long ownerId);
}
