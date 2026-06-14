package com.diving.pungdong.venue.equipment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.venue.VenueScope;
import com.diving.pungdong.venue.VenueService;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentRequest;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import com.diving.pungdong.venue.sync.OfficialVenueCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강사 × 위치 대여 장비 가격표(venue-extension) 관리. 강사 본인 소유(owner=현재 계정)만 다루고,
 * 위치 참조({@code venueRefId})는 저장 전 검증 — CUSTOM 은 내 소유 위치, OFFICIAL 은 Sanity 캐시 존재.
 * 둘 다 아니면 400({@code BadRequestException}, venue 도메인 컨벤션).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueEquipmentService {

    private final VenueEquipmentProfileJpaRepo profileRepo;
    private final VenueService venueService;
    private final OfficialVenueCache officialVenueCache;

    /** 내 가격표 목록 — venueRefId 주면 그 위치만(없으면 빈 목록), 안 주면 전체. */
    public List<VenueEquipmentResponse> listMine(Account me, String venueRefId) {
        List<VenueEquipmentProfile> profiles;
        if (StringUtils.hasText(venueRefId)) {
            profiles = profileRepo.findByOwnerIdAndVenueRefId(me.getId(), venueRefId)
                    .map(List::of).orElseGet(List::of);
        } else {
            profiles = profileRepo.findAllByOwnerIdOrderByIdDesc(me.getId());
        }
        return profiles.stream().map(VenueEquipmentResponse::from).collect(Collectors.toList());
    }

    /** 한 위치의 가격표 저장(upsert) — items 전량 교체 스냅샷. */
    @Transactional
    public VenueEquipmentResponse upsert(Account me, VenueEquipmentRequest req) {
        validateVenueRef(me, req.getVenueRefId());

        VenueEquipmentProfile profile = profileRepo
                .findByOwnerIdAndVenueRefId(me.getId(), req.getVenueRefId())
                .orElseGet(() -> VenueEquipmentProfile.builder()
                        .owner(me).venueRefId(req.getVenueRefId())
                        .createdAt(LocalDateTime.now()).build());

        profile.clearItems();
        List<VenueEquipmentRequest.Item> items = req.getItems() == null ? List.of() : req.getItems();
        for (int i = 0; i < items.size(); i++) {
            profile.addItem(buildItem(items.get(i), i));
        }
        profile.setUpdatedAt(LocalDateTime.now());
        return VenueEquipmentResponse.from(profileRepo.save(profile));
    }

    private VenueEquipmentItem buildItem(VenueEquipmentRequest.Item dto, int sortOrder) {
        if (!StringUtils.hasText(dto.getName())) {
            throw new BadRequestException();
        }
        SizeFormat format = dto.getSizeFormat() == null ? SizeFormat.NONE : dto.getSizeFormat();
        List<String> options = resolveSizeOptions(format, dto.getSizeOptions());
        return VenueEquipmentItem.builder()
                .name(dto.getName().trim())
                .price(dto.getPrice())
                .sizeFormat(format)
                .sizeOptions(options)
                .sortOrder(sortOrder)
                .build();
    }

    /** NONE=항상 빈 목록. 그 외 미입력이면 형식 프리셋, 입력했으면 그대로(강사 override). */
    private List<String> resolveSizeOptions(SizeFormat format, List<String> provided) {
        if (format == SizeFormat.NONE) {
            return new ArrayList<>();
        }
        if (provided == null || provided.isEmpty()) {
            return new ArrayList<>(format.presetOptions());
        }
        return new ArrayList<>(provided);
    }

    private void validateVenueRef(Account me, String venueRefId) {
        VenueScope.Ref ref = VenueScope.parse(venueRefId); // 형식 어긋나면 400
        if (ref.getScope() == VenueScope.CUSTOM) {
            long pk;
            try {
                pk = Long.parseLong(ref.getId());
            } catch (NumberFormatException e) {
                throw new BadRequestException();
            }
            if (!venueService.ownsCustomVenue(me, pk)) {
                throw new BadRequestException();
            }
        } else { // OFFICIAL
            if (!officialVenueCache.contains(ref.getId())) {
                throw new BadRequestException();
            }
        }
    }
}
