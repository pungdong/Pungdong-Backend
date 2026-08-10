package com.diving.pungdong.venue.favorite;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.venue.VenueRefValidator;
import com.diving.pungdong.venue.favorite.dto.VenueFavoriteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강사별 위치 즐겨찾기 — 마크/해제/목록. 신원은 항상 세션({@code @CurrentUser})에서 온다.
 *
 * <p><b>마크는 {@link VenueRefValidator} 를 통과해야 한다</b> — CUSTOM 은 내 소유 위치여야 하고
 * OFFICIAL 은 Sanity 캐시에 존재해야 한다. 아니면 400. 이게 없으면 남의 커스텀 위치 id 를 넣어보며
 * 존재 여부를 떠보는(probe) 통로가 열린다.
 *
 * <p><b>해제는 검증하지 않는다</b> — {@code (owner, ref)} 로 내 행만 지우므로 안전하고, 위치가 이미
 * 삭제된 뒤에도 해제는 되어야 한다(검증하면 지울 수 없는 표식이 남는다).
 *
 * <p>마크/해제 모두 <b>멱등</b>: 이미 있으면 그대로 200, 없는 걸 지워도 204.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueFavoriteService {

    private final VenueFavoriteJpaRepo favoriteRepo;
    private final VenueRefValidator venueRefValidator;

    /** 즐겨찾기 마크 (멱등). 이미 있으면 저장하지 않고 기존 표식(원래 createdAt 포함)을 그대로 돌려준다. */
    @Transactional
    public VenueFavoriteResponse mark(Account me, String venueRefId) {
        venueRefValidator.validate(me, venueRefId);

        VenueFavorite favorite = favoriteRepo.findByOwnerIdAndVenueRefId(me.getId(), venueRefId)
                .orElseGet(() -> favoriteRepo.save(VenueFavorite.builder()
                        .owner(me)
                        .venueRefId(venueRefId)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build()));
        return VenueFavoriteResponse.from(favorite);
    }

    /** 즐겨찾기 해제 (멱등). 없는 걸 지워도 조용히 성공. */
    @Transactional
    public void unmark(Account me, String venueRefId) {
        favoriteRepo.deleteByOwnerIdAndVenueRefId(me.getId(), venueRefId);
    }

    public List<VenueFavoriteResponse> listMine(Account me) {
        return favoriteRepo.findAllByOwnerIdOrderByIdDesc(me.getId()).stream()
                .map(VenueFavoriteResponse::from)
                .collect(Collectors.toList());
    }
}
