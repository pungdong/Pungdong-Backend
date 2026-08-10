package com.diving.pungdong.venue.favorite;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.venue.favorite.dto.VenueFavoriteRequest;
import com.diving.pungdong.venue.favorite.dto.VenueFavoriteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 강사별 위치 즐겨찾기 — 코스빌더 picker 의 "내 위치" 묶음. 위치는 {@code venueRefId} 로 가리키므로
 * 공식·커스텀을 같은 엔드포인트가 다룬다.
 *
 * <p>매처 {@code /venue-favorites/**} → authenticated (강사 트랙; 리뷰 대기 STUDENT 도 draft 준비 허용 —
 * venue·venue-equipment 와 동일). 형제 리소스로 뺀 이유: {@code /venues/{id}} 템플릿과 자리를 다투지
 * 않게 — {@code /venue-equipment} 와 같은 모양.
 *
 * <p>picker 초기 상태는 이 목록을 따로 부르지 않아도 된다 — {@code GET /venues/builder} 각 항목의
 * {@code favorite} 로 함께 온다.
 */
@RestController
@RequestMapping(value = "/venue-favorites", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class VenueFavoriteController {

    private final VenueFavoriteService favoriteService;

    /** 내 즐겨찾기 전체. */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Account account) {
        List<VenueFavoriteResponse> favorites = favoriteService.listMine(account);

        CollectionModel<VenueFavoriteResponse> model = CollectionModel.of(favorites);
        model.add(linkTo(methodOn(VenueFavoriteController.class).list(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-venue-favorites").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 마크 (멱등) — 이미 즐겨찾기한 위치를 다시 보내도 200. */
    @PostMapping
    public ResponseEntity<?> mark(@CurrentUser Account account,
                                  @Valid @RequestBody VenueFavoriteRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        VenueFavoriteResponse saved = favoriteService.mark(account, request.getVenueRefId());

        EntityModel<VenueFavoriteResponse> model = EntityModel.of(saved);
        model.add(Link.of("/docs/api.html#resource-venue-favorites").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 해제 (멱등) — 즐겨찾기하지 않은 위치를 보내도 204.
     *
     * <p>DELETE 본문이 아니라 <b>쿼리 파라미터</b>인 이유: 일부 HTTP 클라이언트·중간 프록시가 DELETE
     * 본문을 떨어뜨린다. {@code venueRefId} 는 PII 가 아니고 콜론은 쿼리 문자열에서 그대로 허용되는
     * 문자라 URL 에 실려도 문제없다 ({@code GET /venue-equipment?venueRefId=} 와 같은 방식).
     */
    @DeleteMapping
    public ResponseEntity<?> unmark(@CurrentUser Account account, @RequestParam String venueRefId) {
        if (!StringUtils.hasText(venueRefId)) {
            throw new BadRequestException();
        }
        favoriteService.unmark(account, venueRefId);
        return ResponseEntity.noContent().build();
    }
}
