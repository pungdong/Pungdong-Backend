package com.diving.pungdong.venue.equipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VenueEquipmentExtensionJpaRepo extends JpaRepository<VenueEquipmentExtension, Long> {

    List<VenueEquipmentExtension> findAllByOwnerIdOrderByIdDesc(Long ownerId);

    Optional<VenueEquipmentExtension> findByOwnerIdAndVenueRefId(Long ownerId, String venueRefId);
}
