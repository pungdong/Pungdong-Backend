package com.diving.pungdong.consent;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.consent.dto.AgreementRef;
import com.diving.pungdong.consent.dto.MyConsentResponse;
import com.diving.pungdong.consent.dto.RecordConsentRequest;
import com.diving.pungdong.consent.dto.RecordConsentResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
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
 * 동의(consent) — 계정 공유 자산. 회원가입/본인확인/강사신청/결제 어느 화면이든 사용자가
 * 약관에 동의하면 여기로 {@code (context, [{key, version}])} 를 보내 이력으로 남긴다.
 * 약관 전문은 FE 가 Sanity 에서 읽어 보여주고, BE 는 그 버전을 박제 + 동의를 기록한다.
 *
 * <p>권한 매처: {@code /consents/**} → authenticated.
 */
@RestController
@RequestMapping(value = "/consents", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    /** 동의 기록. 처음 보는 (key,version) 은 Sanity 에서 전문을 받아 박제 후 참조. */
    @PostMapping
    public ResponseEntity<?> record(@CurrentUser Account account,
                                    @Valid @RequestBody RecordConsentRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        List<AgreementRef> recorded = consentService.record(account, request);

        RecordConsentResponse response = RecordConsentResponse.builder()
                .recorded(recorded.size())
                .agreements(recorded)
                .build();

        EntityModel<RecordConsentResponse> model = EntityModel.of(response);
        model.add(linkTo(methodOn(ConsentController.class).getMine(account)).withRel("me"));
        model.add(Link.of("/docs/api.html#resource-consent-create").withRel("profile"));
        return ResponseEntity.status(201).body(model);
    }

    /** 내 동의 이력 (최신순). 배열은 {@code _embedded.consents}. */
    @GetMapping("/me")
    public ResponseEntity<?> getMine(@CurrentUser Account account) {
        List<MyConsentResponse> consents = consentService.getMyConsents(account);

        CollectionModel<MyConsentResponse> model = CollectionModel.of(consents);
        model.add(linkTo(methodOn(ConsentController.class).getMine(account)).withSelfRel());
        model.add(Link.of("/docs/api.html#resource-consent-me").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
