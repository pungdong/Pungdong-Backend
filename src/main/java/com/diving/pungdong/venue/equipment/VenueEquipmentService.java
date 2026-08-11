package com.diving.pungdong.venue.equipment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.venue.VenueRefValidator;
import com.diving.pungdong.venue.VenueScope;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentRequest;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import com.diving.pungdong.venue.sync.OfficialVenueCache;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 강사 × 위치 대여 장비 가격표(venue-extension) 관리. 강사 본인 소유(owner=현재 계정)만 다루고,
 * 위치 참조({@code venueRefId})는 저장 전 검증 — CUSTOM 은 내 소유 위치, OFFICIAL 은 Sanity 캐시 존재.
 * 둘 다 아니면 400({@code BadRequestException}, venue 도메인 컨벤션).
 *
 * <p><b>prefill fallback</b>({@link #listMine}): venueRefId 지정 조회에서 저장 행이 없고 그 참조가
 * OFFICIAL 이며 캐시 문서에 {@code defaultEquipment} 가 있으면, venue 기본 장비를
 * {@code source=VENUE_DEFAULT} 로 합성해 준다(코스 작성 Step 3 시작값 전용 — booking 경로
 * {@link #findMine} 은 무변경, 학생은 강사 저장분만 본다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueEquipmentService {

    private final VenueEquipmentExtensionJpaRepo extensionRepo;
    private final VenueRefValidator venueRefValidator;
    private final OfficialVenueCache officialVenueCache;

    /**
     * 내 가격표 목록 — venueRefId 주면 그 위치만, 안 주면 전체(무필터 목록에는 fallback 없음).
     * 위치 지정 + 저장 행 없음이면 venue 기본 장비 prefill 시도(OFFICIAL 한정) — 저장 행이 있으면
     * 빈 items 라도 그대로 MINE(강사가 비운 건 비운 것 — 기본값 부활 금지).
     */
    public List<VenueEquipmentResponse> listMine(Account me, String venueRefId) {
        if (StringUtils.hasText(venueRefId)) {
            return extensionRepo.findByOwnerIdAndVenueRefId(me.getId(), venueRefId)
                    .map(e -> List.of(VenueEquipmentResponse.from(e)))
                    .orElseGet(() -> venueDefaultFallback(venueRefId));
        }
        return extensionRepo.findAllByOwnerIdOrderByIdDesc(me.getId())
                .stream().map(VenueEquipmentResponse::from).collect(Collectors.toList());
    }

    /**
     * 저장 행 없는 위치의 prefill 합성 — OFFICIAL + 캐시 문서에 비어있지 않은 {@code defaultEquipment}
     * 일 때만 {@code source=VENUE_DEFAULT}(id null · sizeOptions null). CUSTOM / 기본값 없는
     * OFFICIAL / 깨진 토큰은 기존처럼 빈 목록(조회라 400 을 던지지 않는다).
     *
     * <p>{@code sizeOptions} 는 반드시 null(= "자동") — {@code []} 는 FE 가 "명시적 0개 선택"으로
     * 렌더해(칩 전부 OFF) 저장 시 프리셋 전체가 채워지는 실제 동작과 정반대로 보인다. null 이면 FE 의
     * {@code ?? presets} 폴백으로 프리셋 전체 표시 → 표시=저장 일치(계약서 §3, 2026-08-11 개정).
     */
    private List<VenueEquipmentResponse> venueDefaultFallback(String venueRefId) {
        VenueScope.Ref ref;
        try {
            ref = VenueScope.parse(venueRefId);
        } catch (BadRequestException e) {
            return List.of();
        }
        if (ref.getScope() != VenueScope.OFFICIAL) {
            return List.of();
        }
        JsonNode doc = officialVenueCache.getDoc(ref.getId());
        if (doc == null) {
            return List.of();
        }
        JsonNode defaults = doc.get("defaultEquipment");
        if (defaults == null || !defaults.isArray() || defaults.isEmpty()) {
            return List.of();
        }
        List<VenueEquipmentResponse.Item> items = new ArrayList<>();
        for (JsonNode n : defaults) {
            String name = n.hasNonNull("name") ? n.get("name").asText() : null;
            if (!StringUtils.hasText(name)) {
                continue; // 이름 없는 행은 prefill 불가 — lenient skip
            }
            if (!n.path("price").isNumber()) {
                continue; // price 누락/비숫자도 skip — "미상"을 0원(무료)으로 둔갑시키지 않는다
            }
            items.add(VenueEquipmentResponse.Item.builder()
                    .id(null)
                    .name(name.trim())
                    .price(Math.max(0, n.get("price").asInt()))
                    .sizeFormat(SizeFormat.lenientOrNull(n.path("sizeFormat").asText(null)))
                    .sizeOptions(null) // null = "자동"(프리셋 위임) — [] 금지(위 javadoc)
                    .build());
        }
        if (items.isEmpty()) {
            return List.of();
        }
        return List.of(VenueEquipmentResponse.builder()
                .id(null)
                .venueRefId(venueRefId)
                .items(items)
                .source(VenueEquipmentResponse.Source.VENUE_DEFAULT)
                .build());
    }

    /** 한 위치의 내 가격표(코스 읽기 시 위치별 장비 합성용). 없으면 empty. */
    public Optional<VenueEquipmentResponse> findMine(Account me, String venueRefId) {
        return extensionRepo.findByOwnerIdAndVenueRefId(me.getId(), venueRefId).map(VenueEquipmentResponse::from);
    }

    /** 한 위치의 가격표 저장(upsert) — items 전량 교체 스냅샷. */
    @Transactional
    public VenueEquipmentResponse upsert(Account me, VenueEquipmentRequest req) {
        validateVenueRef(me, req.getVenueRefId());

        VenueEquipmentExtension extension = extensionRepo
                .findByOwnerIdAndVenueRefId(me.getId(), req.getVenueRefId())
                .orElseGet(() -> VenueEquipmentExtension.builder()
                        .owner(me).venueRefId(req.getVenueRefId())
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        extension.clearItems();
        List<VenueEquipmentRequest.Item> items = req.getItems() == null ? List.of() : req.getItems();
        for (int i = 0; i < items.size(); i++) {
            extension.addItem(buildItem(items.get(i), i));
        }
        extension.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return VenueEquipmentResponse.from(extensionRepo.save(extension));
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
        venueRefValidator.validate(me, venueRefId);
    }
}
