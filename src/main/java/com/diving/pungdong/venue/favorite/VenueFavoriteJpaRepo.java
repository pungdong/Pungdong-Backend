package com.diving.pungdong.venue.favorite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface VenueFavoriteJpaRepo extends JpaRepository<VenueFavorite, Long> {

    List<VenueFavorite> findAllByOwnerIdOrderByIdDesc(Long ownerId);

    Optional<VenueFavorite> findByOwnerIdAndVenueRefId(Long ownerId, String venueRefId);

    void deleteByOwnerIdAndVenueRefId(Long ownerId, String venueRefId);

    /** 커스텀 위치가 삭제될 때 그 위치를 가리키는 모든 강사의 표식 정리 — 고아 행 방지. */
    void deleteByVenueRefId(String venueRefId);

    /** 빌더/목록 응답 마킹용 — 엔티티 적재 없이 토큰 집합만. */
    @Query("select f.venueRefId from VenueFavorite f where f.owner.id = :ownerId")
    Set<String> findRefsByOwnerId(@Param("ownerId") Long ownerId);
}
