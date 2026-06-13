package com.diving.pungdong.venue;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.venue.dto.VenueCreateRequest;
import com.diving.pungdong.venue.dto.VenueResponse;
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
 * 강사 — 커스텀(CUSTOM) 위치 생성/관리 + 코스 빌더용 위치 카탈로그 읽기.
 *
 * <p>매처: {@code /venues/**} → authenticated ({@code global/security/SecurityConfiguration}). 역할이
 * 아니라 인증으로 둔 이유: 강사 신청 후 리뷰 대기(PENDING) 중인 사람은 아직 STUDENT 라서 — 그 사이에도
 * draft 준비(커스텀 위치 추가)를 허용한다. 실제 커스텀 생성 게이트(그 종목 신청 보유)는 서비스에서 강제.
 *
 * <p>읽기({@code GET /venues})는 <b>내 커스텀 위치만</b>(관리용) — 남의 커스텀은 비공개. 공식(OFFICIAL)
 * 위치는 BE 가 아니라 Sanity. 코스 빌더 official+custom 통합은 후속 BE 머지 엔드포인트가 official(Sanity
 * 서버사이드 읽기)+custom(DB)을 합쳐 반환(FE 소스 무지) — course 생성과 함께. 상세 docs/features/venue.md.
 */
@RestController
@RequestMapping(value = "/venues", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    /** 커스텀 위치 생성 — lockedDisciplineCode 필수, owner=현재 계정. */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody VenueCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(model(venueService.create(account, request)));
    }

    /** 코스 빌더 소비용 — 내 커스텀 위치, 종목/유형 필터. (OFFICIAL 은 FE 가 Sanity 에서 합침.) */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Account account,
                                  @RequestParam(required = false) String disciplineCode,
                                  @RequestParam(required = false) VenueType type) {
        List<VenueResponse> venues = venueService.listMine(account, disciplineCode, type);

        CollectionModel<VenueResponse> model = CollectionModel.of(venues);
        model.add(linkTo(methodOn(VenueController.class).list(account, disciplineCode, type)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-venues").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 코스 빌더 통합 목록 — OFFICIAL(Sanity 캐시) + 내 CUSTOM(DB) 을 BE 가 합쳐 반환. 강사는 "위치목록"
     * 하나만 요청하면 출처가 섞여 온다(FE 소스 무지). 응답 항목의 {@code scope}/{@code venueRefId} 로 구분.
     */
    @GetMapping("/builder")
    public ResponseEntity<?> builder(@CurrentUser Account account,
                                     @RequestParam(required = false) String disciplineCode,
                                     @RequestParam(required = false) VenueType type) {
        List<VenueResponse> venues = venueService.listForBuilder(account, disciplineCode, type);

        CollectionModel<VenueResponse> model = CollectionModel.of(venues);
        model.add(linkTo(methodOn(VenueController.class).builder(account, disciplineCode, type)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-venues-builder").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(venueService.getMine(account, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser Account account, @PathVariable Long id,
                                    @Valid @RequestBody VenueCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok().body(model(venueService.update(account, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long id) {
        venueService.delete(account, id);
        return ResponseEntity.noContent().build();
    }

    private EntityModel<VenueResponse> model(VenueResponse response) {
        EntityModel<VenueResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-venues").withRel("profile"));
        return model;
    }
}
