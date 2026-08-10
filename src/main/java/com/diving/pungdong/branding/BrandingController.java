package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.dto.BrandingUpdateRequest;
import com.diving.pungdong.branding.dto.MyBrandingResponse;
import com.diving.pungdong.branding.dto.PublishRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 브랜딩 페이지 오너 편집 — {@code /branding/me/**} (인증).
 *
 * <p><b>매처가 {@code hasRole("INSTRUCTOR")} 가 아니라 {@code authenticated()} 인 이유</b>: 일반
 * 유저도 "내 프로필"을 쓰고(D2), 강사도 승인 전(pending/rejected)에 편집 화면이 존재한다. 승인 전에는
 * {@code ROLE_INSTRUCTOR} 가 없어 role 로 막으면 그 화면이 403 이 된다. 레포도 같은 이유로
 * {@code /courses/**} 를 {@code authenticated()} 로 둔다.
 *
 * <p><b>생성 엔드포인트가 없다</b> — 첫 쓰기가 곧 생성(upsert)이다. 조회는 생성하지 않는다(§4.5).
 * 신원은 항상 {@code @CurrentUser} 에서 오고 account id 를 파라미터로 받지 않는다(anti-IDOR).
 */
@RestController
@RequestMapping(value = "/branding/me", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class BrandingController {

    private final BrandingService brandingService;

    /** 편집용 원본. 미생성이면 200 + {@code {"exists": false}} — 400 이 아니다(정상 상태). */
    @GetMapping
    public ResponseEntity<?> myBranding(@CurrentUser Account account) {
        return ResponseEntity.ok().body(model(brandingService.myBranding(account)));
    }

    /** 부분 수정 — 보낸 키만 반영, 명시적 null 은 비우기. 미생성이면 생성(upsert). */
    @PatchMapping
    public ResponseEntity<?> updateMyBranding(@CurrentUser Account account,
                                              @Valid @RequestBody BrandingUpdateRequest request,
                                              BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok().body(model(brandingService.updateMyBranding(account, request)));
    }

    /** 발행 토글 — 승인 게이트 없음(D2). 미생성이면 생성(upsert). */
    @PatchMapping("/publish")
    public ResponseEntity<?> updatePublished(@CurrentUser Account account,
                                             @Valid @RequestBody PublishRequest request,
                                             BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok()
                .body(model(brandingService.updatePublished(account, request.getPublished())));
    }

    private EntityModel<MyBrandingResponse> model(MyBrandingResponse response) {
        EntityModel<MyBrandingResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-branding-me").withRel("profile"));
        return model;
    }
}
