package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.community.dto.ContentReportRequest;
import com.diving.pungdong.community.dto.ContentReportResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * 신고 접수 + 어드민 큐 (승인안 2-A — <b>사람이 보는 큐</b>).
 *
 * <p><b>자동 숨김 임계값은 없다.</b> 신고 N건 누적 시 자동 비공개하는 방식은 조직적 신고로 정상 글이
 * 사라지는 위험이 어드민 부재 시간대의 노출보다 크고, 임계값은 실데이터 없이 정하면 감에 불과하다.
 * 필요해지면 {@code auto_hidden_at} 컬럼과 카운트 조건만 얹으면 된다.
 *
 * <p><b>중복 신고는 에러가 아니다.</b> 이미 신고한 대상을 다시 눌러도 사용자 입장에선 "신고됨" 이 맞는
 * 결과다 — 기존 건을 그대로 돌려준다(레포 규칙: 기대되는 결과는 4xx 가 아니다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentReportService {

    /** 어드민 큐에 싣는 본문 미리보기 길이. 훑어보는 화면이라 전문은 싣지 않는다. */
    private static final int PREVIEW_LENGTH = 80;

    private final ContentReportJpaRepo reportRepo;
    private final CommunityPostJpaRepo postRepo;
    private final CommunityCommentJpaRepo commentRepo;
    private final AccountJpaRepo accountRepo;

    /* ─── 접수 ───────────────────────────────────────────── */

    @Transactional
    public ContentReportResponse report(Account currentUser, ContentReportRequest request) {
        if (request.getReason() == ReportReason.OTHER && !StringUtils.hasText(request.getDetail())) {
            throw new BadRequestException("기타 사유는 설명을 함께 적어주세요.");
        }

        Account me = loadAccount(currentUser);
        Long authorId = requireTargetAuthor(request.getTargetType(), request.getTargetId());

        // 자기 콘텐츠 신고는 막는다 — 어드민 큐만 늘리고 판단할 게 없다.
        if (Objects.equals(authorId, me.getId())) {
            throw new BadRequestException("자신의 글이나 댓글은 신고할 수 없어요.");
        }

        // 중복은 멱등 — 기존 건을 그대로 돌려준다.
        Optional<ContentReport> existing = reportRepo.findByTargetTypeAndTargetIdAndReporterId(
                request.getTargetType(), request.getTargetId(), me.getId());
        if (existing.isPresent()) {
            return toResponse(existing.get(), false);
        }

        ContentReport saved = reportRepo.save(ContentReport.builder()
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .reporter(me)
                .reason(request.getReason())
                .detail(request.getDetail())
                .status(ReportStatus.PENDING)
                .build());
        return toResponse(saved, false);
    }

    /* ─── 어드민 ─────────────────────────────────────────── */

    /** 큐 목록. {@code status} 생략이면 전체 탭. 최신 접수순. */
    public Page<ContentReportResponse> queue(ReportStatus status, Pageable pageable) {
        Page<ContentReport> page = status == null
                ? reportRepo.findAllByOrderByCreatedAtDesc(pageable)
                : reportRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(report -> toResponse(report, true));
    }

    /** 탭 뱃지용 상태별 건수. */
    public ReportCounts counts() {
        return new ReportCounts(
                reportRepo.countByStatus(ReportStatus.PENDING),
                reportRepo.countByStatus(ReportStatus.ACTIONED),
                reportRepo.countByStatus(ReportStatus.DISMISSED));
    }

    /**
     * 처리 — 조치(대상 숨김) 또는 기각.
     *
     * <p>{@code ACTIONED} 는 <b>대상 콘텐츠를 실제로 숨긴다.</b> 상태만 바꾸고 콘텐츠를 그대로 두면
     * 어드민이 "처리했다" 고 믿는데 신고된 글이 계속 보이는, 가장 나쁜 종류의 어긋남이 생긴다.
     * 게시물은 {@code isHidden}(되돌릴 수 있음), 댓글은 soft delete 로 가린다.
     */
    @Transactional
    public ContentReportResponse handle(Long reportId, ReportStatus decision) {
        if (decision != ReportStatus.ACTIONED && decision != ReportStatus.DISMISSED) {
            throw new BadRequestException("처리 결과는 조치 또는 기각이어야 해요.");
        }
        ContentReport report = reportRepo.findById(reportId).orElseThrow(ResourceNotFoundException::new);

        if (decision == ReportStatus.ACTIONED) {
            hideTarget(report);
        }
        report.setStatus(decision);
        report.setHandledAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(report, true);
    }

    /* ─── 내부 ───────────────────────────────────────────── */

    /**
     * 대상이 실제로 있는지 확인하고 작성자 id 를 돌려준다.
     *
     * <p>폴리모픽 참조라 DB 제약을 걸 수 없어 접수 시점에 여기서 확인한다 — 없는 대상을 신고하면
     * 어드민 큐에 열 수 없는 행이 쌓인다.
     */
    private Long requireTargetAuthor(ReportTargetType targetType, Long targetId) {
        if (targetType == ReportTargetType.POST) {
            return postRepo.findById(targetId)
                    .map(post -> post.getBranding().getAccount().getId())
                    .orElseThrow(ResourceNotFoundException::new);
        }
        return commentRepo.findById(targetId)
                .map(comment -> comment.getAccount().getId())
                .orElseThrow(ResourceNotFoundException::new);
    }

    private void hideTarget(ContentReport report) {
        if (report.getTargetType() == ReportTargetType.POST) {
            postRepo.findById(report.getTargetId()).ifPresent(post -> post.setHidden(true));
            return;
        }
        commentRepo.findById(report.getTargetId()).ifPresent(comment -> {
            comment.setDeleted(true);
            comment.setBody("삭제된 댓글입니다.");
        });
    }

    private ContentReportResponse toResponse(ContentReport report, boolean forAdmin) {
        return ContentReportResponse.builder()
                .id(report.getId())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reason(report.getReason())
                .detail(report.getDetail())
                .status(report.getStatus())
                .createdAt(report.getCreatedAt())
                .handledAt(report.getHandledAt())
                .reporterNickName(forAdmin ? report.getReporter().getNickName() : null)
                .targetPreview(forAdmin ? previewOf(report) : null)
                .build();
    }

    /** 대상 본문 앞부분. 이미 지워졌으면 null — 어드민 화면이 "대상 없음" 으로 렌더한다. */
    private String previewOf(ContentReport report) {
        String body = report.getTargetType() == ReportTargetType.POST
                ? postRepo.findById(report.getTargetId()).map(this::postPreview).orElse(null)
                : commentRepo.findById(report.getTargetId()).map(CommunityComment::getBody).orElse(null);
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH);
    }

    private String postPreview(BrandingPost post) {
        return StringUtils.hasText(post.getTitle()) ? post.getTitle() : post.getCaption();
    }

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }

    /** 어드민 탭 뱃지 — 상태별 건수. */
    public record ReportCounts(long pending, long actioned, long dismissed) {
    }
}
