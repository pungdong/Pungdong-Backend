package com.diving.pungdong.venue.equipment;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentRequest;
import com.diving.pungdong.venue.equipment.dto.VenueEquipmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * 강사 대여 장비 가격표(venue-extension) — 강사 본인 소유. 위치(official/custom)별 1장, 코스 간 공유.
 *
 * <p>매처 {@code /venue-equipment/**} → authenticated (강사 트랙; 리뷰 대기 STUDENT 도 draft 준비 허용 —
 * venue 와 동일). 코스는 이 가격표를 위치 참조로 합성해 보여준다(후속 PR).
 */
@RestController
@RequestMapping(value = "/venue-equipment", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class VenueEquipmentController {

    private final VenueEquipmentService equipmentService;

    /** 내 가격표 — venueRefId 주면 그 위치만, 안 주면 전체. */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Account account,
                                  @RequestParam(required = false) String venueRefId) {
        List<VenueEquipmentResponse> profiles = equipmentService.listMine(account, venueRefId);

        CollectionModel<VenueEquipmentResponse> model = CollectionModel.of(profiles);
        model.add(linkTo(methodOn(VenueEquipmentController.class).list(account, venueRefId)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-venue-equipment").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /** 한 위치의 가격표 저장(upsert) — items 전량 교체. */
    @PutMapping
    public ResponseEntity<?> upsert(@CurrentUser Account account,
                                    @Valid @RequestBody VenueEquipmentRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        VenueEquipmentResponse saved = equipmentService.upsert(account, request);

        EntityModel<VenueEquipmentResponse> model = EntityModel.of(saved);
        model.add(Link.of("/docs/api.html#resource-venue-equipment").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
