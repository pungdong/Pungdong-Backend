package com.diving.pungdong.venue.equipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueEquipmentProfileJpaRepo extends JpaRepository<VenueEquipmentProfile, Long> {

    List<VenueEquipmentProfile> findAllByOwnerIdOrderByIdDesc(Long ownerId);

    Optional<VenueEquipmentProfile> findByOwnerIdAndVenueRefId(Long ownerId, String venueRefId);
}
