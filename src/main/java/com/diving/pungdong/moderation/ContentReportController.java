package com.diving.pungdong.moderation;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.moderation.dto.ContentReportRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 신고 접수 (인증). 어드민 처리는 {@link AdminContentReportController}.
 *
 * <p>중복 신고는 <b>200 멱등</b>이다 — 이미 신고한 걸 다시 눌러도 사용자 입장에선 "신고됨" 이 맞는 결과다.
 *
 * <p><b>경로가 둘인 건 한시적이다.</b> 대상이 커뮤니티 밖(강의·채팅)으로 넓어지면서 정식 경로는
 * {@code /reports} 가 됐고, {@code /community/reports} 는 이미 붙어 있는 클라이언트를 위한 별칭이다.
 * FE 셋이 옮기면 별칭을 지운다(커뮤니티는 prod 에 배포된 적이 없어 하위호환 부담이 크지 않다).
 */
@RestController
@RequestMapping(value = {"/reports", "/community/reports"}, produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class ContentReportController {

    private final ContentReportService reportService;

    @PostMapping
    public ResponseEntity<?> report(@CurrentUser Account account,
                                    @Valid @RequestBody ContentReportRequest request,
                                    BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException(result.getFieldError().getDefaultMessage());
        }
        return ResponseEntity.ok().body(EntityModel.of(reportService.report(account, request)));
    }
}
