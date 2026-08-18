package com.diving.pungdong.block;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.block.dto.BlockRequest;
import com.diving.pungdong.block.dto.BlockedAccountResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 유저 차단 (전부 인증 필요).
 *
 * <p><b>대상은 닉네임이다</b> — 계정 id 를 계약에 노출하지 않는다(anti-IDOR). 공개 프로필
 * {@code GET /instructors/{nickName}} 과 같은 식별자라 클라이언트가 이미 들고 있다.
 *
 * <p>⚠️ 닉네임에 {@code /} 나 {@code \} 가 들어가면 Spring Security 의 {@code StrictHttpFirewall} 이
 * 요청 자체를 거절한다 — 공개 프로필이 이미 갖고 있는 한계를 그대로 물려받는다(방화벽은 의도적으로
 * 완화하지 않는다). 그런 닉네임은 애초에 프로필도 열리지 않는다.
 *
 * <p>차단하면 <b>양쪽 모두</b> 상대의 글·댓글이 커뮤니티 표면에서 사라진다. 다만 수강·일정·결제·단체
 * 채팅은 그대로다 — 정책은 {@code docs/features/moderation.md}.
 */
@RestController
@RequestMapping(value = "/blocks", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    /** 차단. <b>중복은 200 멱등</b>(기존 건을 그대로 돌려준다). 자기 자신·없는 닉네임은 400. */
    @PostMapping
    public ResponseEntity<?> block(@CurrentUser Account account,
                                   @Valid @RequestBody BlockRequest request,
                                   BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok().body(EntityModel.of(blockService.block(account, request.getNickName())));
    }

    /** 해제. 차단돼 있지 않아도 204 — 결과 상태가 같으므로 에러가 아니다. */
    @DeleteMapping("/{nickName}")
    public ResponseEntity<?> unblock(@CurrentUser Account account, @PathVariable String nickName) {
        blockService.unblock(account, nickName);
        return ResponseEntity.noContent().build();
    }

    /** 차단 관리 화면 — 내가 차단한 사람 목록. 배열은 {@code _embedded.blockedAccounts}. */
    @GetMapping
    public ResponseEntity<?> myBlocks(@CurrentUser Account account,
                                      @PageableDefault(size = 20) Pageable pageable,
                                      PagedResourcesAssembler<BlockedAccountResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(blockService.myBlocks(account, pageable)));
    }
}
