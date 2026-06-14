package com.diving.pungdong.venue;

import com.diving.pungdong.venue.dto.VenueResponse;
import com.diving.pungdong.venue.sync.OfficialVenueCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code venueRefId} → 표시 정보({@link Resolved} 이름 + {@link Region})로 해석한다. {@link VenueRefValidator}
 * 가 venueRefId <i>검증</i>의 단일 출처라면, 이건 같은 토큰을 <i>읽기용 메타</i>로 푸는 짝 — 코스 저장 시
 * 회차 위치들의 주소에서 지역/대표 위치명을 비정규화할 때 쓴다.
 *
 * <p>CUSTOM 은 BE DB({@link VenueJpaRepo}), OFFICIAL 은 Sanity 캐시({@link OfficialVenueCache#getAll()}).
 * 검증과 달리 소유 여부는 안 본다(저장 직전 {@code VenueRefValidator} 가 이미 보장). 존재하지 않으면 결과에서
 * 빠진다(스킵).
 */
@Component
@RequiredArgsConstructor
public class VenueRefResolver {

    private final VenueJpaRepo venueJpaRepo;
    private final OfficialVenueCache officialVenueCache;

    /** 표시 정보 — 위치 이름 + 지역 묶음. */
    public static final class Resolved {
        private final String name;
        private final Region region;

        public Resolved(String name, Region region) {
            this.name = name;
            this.region = region;
        }

        public String getName() { return name; }
        public Region getRegion() { return region; }
    }

    /**
     * 여러 venueRefId 를 한 번에 해석. OFFICIAL 카탈로그는 한 번만 적재(작은 카탈로그). 입력 순서·중복
     * 무관, 키는 venueRefId. 해석 실패(미존재/형식오류)는 결과에서 제외.
     */
    public Map<String, Resolved> resolveAll(Collection<String> venueRefIds) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        resolveVenues(venueRefIds).forEach((ref, v) ->
                out.put(ref, new Resolved(v.getName(), Region.fromAddress(v.getAddress()))));
        return out;
    }

    /**
     * venueRefId → 전체 {@link VenueResponse}(이름·주소·type·이용권·daypart fee). 공개 코스 상세에서
     * 입장료(이용권×daypart fee)·위치명·장비를 합성할 때 쓴다. CUSTOM=BE DB, OFFICIAL=Sanity 캐시.
     * 입력 순서·중복 무관, 키는 venueRefId. 미존재/형식오류는 결과에서 제외.
     */
    public Map<String, VenueResponse> resolveVenues(Collection<String> venueRefIds) {
        Map<String, VenueResponse> out = new LinkedHashMap<>();
        Map<String, VenueResponse> officialByRef = null;
        for (String ref : venueRefIds) {
            if (ref == null || out.containsKey(ref)) {
                continue;
            }
            VenueScope.Ref parsed;
            try {
                parsed = VenueScope.parse(ref);
            } catch (RuntimeException e) {
                continue; // 형식 어긋남 — 방어적으로 스킵
            }
            if (parsed.getScope() == VenueScope.CUSTOM) {
                long pk;
                try {
                    pk = Long.parseLong(parsed.getId());
                } catch (NumberFormatException e) {
                    continue;
                }
                venueJpaRepo.findById(pk).ifPresent(v -> out.put(ref, VenueResponse.from(v)));
            } else {
                if (officialByRef == null) {
                    officialByRef = loadOfficialByRef();
                }
                VenueResponse v = officialByRef.get(ref);
                if (v != null) {
                    out.put(ref, v);
                }
            }
        }
        return out;
    }

    private Map<String, VenueResponse> loadOfficialByRef() {
        List<VenueResponse> all = officialVenueCache.getAll();
        return all.stream().collect(Collectors.toMap(
                VenueResponse::getVenueRefId, Function.identity(), (a, b) -> a));
    }
}
