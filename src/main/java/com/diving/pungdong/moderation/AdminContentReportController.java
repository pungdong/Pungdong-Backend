package com.diving.pungdong.moderation;

import com.diving.pungdong.moderation.dto.ContentReportResponse;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 신고 처리 큐 (어드민 전용). 매처: {@code /admin/community/reports/**} hasRole(ADMIN).
 *
 * <p>강사 신청 심사 화면과 같은 모양이다 — 목록 + {@code /counts} 탭 뱃지 + 건별 처리.
 * 어드민 FE 가 이미 그 패턴을 쓰고 있어 새 화면 관례를 만들 이유가 없다.
 */
@RestController
@RequestMapping(value = {"/admin/reports", "/admin/community/reports"}, produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminContentReportController {

    private final ContentReportService reportService;

    /**
     * 큐 목록. {@code status}·{@code targetType} 둘 다 생략 가능(생략 = 그 축 전체). 최신 접수순.
     *
     * <p>{@code targetType} 이 어드민 화면의 <b>항목 탭</b>이다(커뮤니티글 · 댓글 · 강의 · 채팅).
     * 링크는 BE 가 만들지 않는다 — 어드민 FE 가 {@code targetType}+{@code targetId} 로 해당 글·상품
     * 페이지 URL 을 조립한다(알림 딥링크와 같은 기존 규칙).
     *
     * <p>{@code targetAuthorNickName} 은 <b>사람</b> 축이다 — 행의 {@code targetAuthorReportCount}
     * 를 누르면 그 사람에 대한 신고만 모인다. 대상이 넷으로 흩어져 있어 같은 강사의 여러 강의에 걸친
     * 반복 신고가 큐에서 서로 만나지 못하던 구멍을 메운다. 없는 닉네임이면 빈 페이지(400 아님).
     */
    @GetMapping
    public ResponseEntity<?> queue(@RequestParam(required = false) ReportStatus status,
                                   @RequestParam(required = false) ReportTargetType targetType,
                                   @RequestParam(required = false) String targetAuthorNickName,
                                   @PageableDefault(size = 20, sort = "createdAt",
                                           direction = Sort.Direction.DESC) Pageable pageable,
                                   PagedResourcesAssembler<ContentReportResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(
                reportService.queue(status, targetType, targetAuthorNickName, pageable)));
    }

    /** 상태별 건수 — 탭 뱃지(대기/조치/기각). */
    @GetMapping("/counts")
    public ResponseEntity<?> counts() {
        return ResponseEntity.ok().body(EntityModel.of(reportService.counts()));
    }

    /**
     * 처리 — 조치 또는 기각.
     *
     * <p>조치({@code ACTIONED})는 <b>대상 콘텐츠를 실제로 숨긴다.</b> 상태만 바꾸면 어드민이
     * "처리했다" 고 믿는데 신고된 글이 계속 보이는 어긋남이 생긴다.
     */
    @PatchMapping("/{reportId}")
    public ResponseEntity<?> handle(@PathVariable Long reportId,
                                    @RequestBody @Valid HandleRequest request) {
        return ResponseEntity.ok().body(EntityModel.of(
                reportService.handle(reportId, request.getStatus(), request.getNote())));
    }

    @Getter @Setter
    @NoArgsConstructor
    public static class HandleRequest {
        /** {@code ACTIONED}(조치) 또는 {@code DISMISSED}(기각). 그 외 값은 400. */
        @NotNull
        private ReportStatus status;

        /**
         * 처리 메모(선택, 500자). 판단 근거는 <b>처리 시점에만 존재하는 정보</b>다 — 없으면 "따로
         * 경고했다" 와 "문제없음" 이 똑같은 기각 행으로 남는다. 비워 보내면 기존 메모를 지우지 않는다.
         */
        @Size(max = 500, message = "처리 메모는 500자까지 쓸 수 있어요.")
        private String note;
    }
}
