package com.diving.pungdong.community;

import com.diving.pungdong.community.dto.ContentReportResponse;
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

import javax.validation.constraints.NotNull;

/**
 * 신고 처리 큐 (어드민 전용). 매처: {@code /admin/community/reports/**} hasRole(ADMIN).
 *
 * <p>강사 신청 심사 화면과 같은 모양이다 — 목록 + {@code /counts} 탭 뱃지 + 건별 처리.
 * 어드민 FE 가 이미 그 패턴을 쓰고 있어 새 화면 관례를 만들 이유가 없다.
 */
@RestController
@RequestMapping(value = "/admin/community/reports", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AdminContentReportController {

    private final ContentReportService reportService;

    /** 큐 목록. {@code status} 생략이면 전체 탭. 최신 접수순. */
    @GetMapping
    public ResponseEntity<?> queue(@RequestParam(required = false) ReportStatus status,
                                   @PageableDefault(size = 20, sort = "createdAt",
                                           direction = Sort.Direction.DESC) Pageable pageable,
                                   PagedResourcesAssembler<ContentReportResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(reportService.queue(status, pageable)));
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
                                    @RequestBody HandleRequest request) {
        return ResponseEntity.ok().body(EntityModel.of(reportService.handle(reportId, request.getStatus())));
    }

    @Getter @Setter
    @NoArgsConstructor
    public static class HandleRequest {
        /** {@code ACTIONED}(조치) 또는 {@code DISMISSED}(기각). 그 외 값은 400. */
        @NotNull
        private ReportStatus status;
    }
}
