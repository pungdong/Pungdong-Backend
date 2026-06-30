package com.diving.pungdong.profile;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.security.CurrentUser;
import com.diving.pungdong.profile.dto.AccountProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 프로필 카드 — {@code GET /account/profile}(인증·본인). 기존 {@code GET /account}(AccountBasicInfo)에
 * 프로필 사진 + 자격 뱃지를 더한 통합 응답. 매처는 {@code SecurityConfiguration} 의 {@code anyRequest().authenticated()}
 * 가 커버(별도 permitAll 없음).
 *
 * <p>컨트롤러가 account 패키지가 아니라 {@code profile} 패키지에 있는 이유: 자격(certs)이 instructorapplication
 * 소유라 합성하려면 두 도메인에 의존해야 하는데, account 가 feature 도메인을 import 하지 않는다는 단방향 규칙을
 * 지키기 위해 합성을 별도 패키지로 뺀다.
 */
@RestController
@RequestMapping(value = "/account", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/profile")
    public ResponseEntity<?> myProfile(@CurrentUser Account account) {
        EntityModel<AccountProfileResponse> model = EntityModel.of(profileService.myProfile(account));
        model.add(Link.of("/docs/api.html#resource-account-profile").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
