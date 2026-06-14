package com.diving.pungdong.venue;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.venue.sync.OfficialVenueCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code venueRefId} 토큰("CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;") 검증 단일 출처 — 장비 가격표
 * (venue.equipment)와 코스 회차 위치(course)가 같은 규칙으로 검증한다. CUSTOM 은 내 소유 위치,
 * OFFICIAL 은 Sanity 캐시 존재. 어느 것도 아니면 400(venue 도메인 컨벤션, 남의 것 존재 숨김).
 */
@Component
@RequiredArgsConstructor
public class VenueRefValidator {

    private final VenueService venueService;
    private final OfficialVenueCache officialVenueCache;

    public void validate(Account me, String venueRefId) {
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
