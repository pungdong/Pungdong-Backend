package com.diving.pungdong.branding;

import com.diving.pungdong.branding.dto.BrandingProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 브랜딩 페이지 / 내 프로필 — {@code GET /instructors/{nickName}} (<b>비로그인 가능</b>).
 *
 * <p><b>왜 id 가 아니라 닉네임인가</b>: 순차 id 를 공개 URL 에 노출하면 열거로 전수 스크래핑이 된다
 * (루트 CLAUDE.md 의 anti-IDOR 규칙). 전용 handle 을 신설하는 안도 있었으나 사용자 결정(D3)으로
 * <b>닉네임을 URL 식별자로</b> 쓴다 — 대신 닉네임에 형식 가드·예약어 차단이 붙는다.
 *
 * <p>경로가 기존 {@code GET /instructors/public}(공개 강사 목록)과 한 네임스페이스를 쓴다. Spring MVC 는
 * <b>리터럴을 path variable 보다 우선</b>하므로 라우팅은 안전하다. 다만 닉네임이 정확히 {@code "public"}
 * 인 계정은 프로필이 열리지 않으므로 예약어로 막는다.
 *
 * <p>없는 닉네임·미발행·탈퇴는 전부 <b>400(존재 숨김)</b> — 이 레포는 404 를 쓰지 않는다.
 */
@RestController
@RequestMapping(value = "/instructors", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicBrandingController {

    private final BrandingService brandingService;

    /**
     * {@code nickName} 은 percent-encoding 으로 전달된다(한글·공백 등). Spring 이 <b>디코딩된 값</b>을
     * 넘겨주므로 여기서 추가 디코딩을 하면 안 된다(이중 디코딩 버그).
     */
    @GetMapping("/{nickName}")
    public ResponseEntity<?> publicProfile(@PathVariable String nickName) {
        EntityModel<BrandingProfileResponse> model =
                EntityModel.of(brandingService.publicProfile(nickName));
        model.add(Link.of("/docs/api.html#resource-branding-public").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
