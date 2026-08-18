package com.diving.pungdong.moderation;

import com.diving.pungdong.global.persistence.PageClamp;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.branding.BrandingPost;
import com.diving.pungdong.chat.ChatMessageService;
import com.diving.pungdong.community.CommunityComment;
import com.diving.pungdong.community.CommunityCommentJpaRepo;
import com.diving.pungdong.community.CommunityCommentService;
import com.diving.pungdong.community.CommunityPostJpaRepo;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.moderation.dto.ContentReportRequest;
import com.diving.pungdong.moderation.dto.ContentReportResponse;
import com.diving.pungdong.global.persistence.IdempotentInsert;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    /** 조치 시 댓글 삭제 규칙을 재사용한다 — 같은 규칙을 두 곳에 쓰지 않기 위해. */
    private final CommunityCommentService commentService;
    private final CourseJpaRepo courseRepo;
    /** 채팅은 방 접근 권한 판정과 툼스톤 세우기를 자기 도메인에 두고 seam 만 노출한다. */
    private final ChatMessageService chatMessageService;
    private final AccountJpaRepo accountRepo;
    /** 동시 중복 신고 — 제약 위반을 별도 트랜잭션에 가둔다. */
    private final IdempotentInsert idempotentInsert;

    /* ─── 접수 ───────────────────────────────────────────── */

    @Transactional
    public ContentReportResponse report(Account currentUser, ContentReportRequest request) {
        if (request.getReason() == ReportReason.OTHER && !StringUtils.hasText(request.getDetail())) {
            throw new BadRequestException("기타 사유는 설명을 함께 적어주세요.");
        }

        Account me = loadAccount(currentUser);
        Long authorId = requireTargetAuthor(me, request.getTargetType(), request.getTargetId());

        // 자기 콘텐츠 신고는 막는다 — 어드민 큐만 늘리고 판단할 게 없다.
        if (Objects.equals(authorId, me.getId())) {
            throw new BadRequestException("자신이 올린 것은 신고할 수 없어요.");
        }

        // 중복은 멱등 — 기존 건을 그대로 돌려준다.
        Optional<ContentReport> existing = reportRepo.findByTargetTypeAndTargetIdAndReporterId(
                request.getTargetType(), request.getTargetId(), me.getId());
        if (existing.isPresent()) {
            return toResponse(existing.get(), false);
        }

        ContentReport report = ContentReport.builder()
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .reporter(me)
                .reason(request.getReason())
                .detail(request.getDetail())
                .status(ReportStatus.PENDING)
                .build();
        try {
            // 삽입을 별도 트랜잭션에 격리한다 — 위 조회와 이 삽입 사이에 같은 사람의 두 번째 요청이
            // 끼면 UNIQUE 가 걸리는데, 격리 없이 잡으면 이 트랜잭션이 오염돼 결국 500 이 난다.
            idempotentInsert.insert(reportRepo, report);
        } catch (DataIntegrityViolationException alreadyReported) {
            return reportRepo.findByTargetTypeAndTargetIdAndReporterId(
                            request.getTargetType(), request.getTargetId(), me.getId())
                    .map(existingReport -> toResponse(existingReport, false))
                    .orElseThrow(ResourceNotFoundException::new);
        }
        return toResponse(report, false);
    }

    /* ─── 어드민 ─────────────────────────────────────────── */

    /** 큐 목록. {@code status} 생략이면 전체 탭. 최신 접수순. */
    public Page<ContentReportResponse> queue(ReportStatus status, ReportTargetType targetType, Pageable pageable) {
        // 피드와 같은 규칙 — 클라이언트 정렬을 버리고 크기 상한을 건다. 어드민이라고 열어두면
        // ?size=100000 한 방에 신고 전량이 미리보기까지 붙어 나온다.
        Pageable fixed = PageClamp.fixed(pageable);
        return reportRepo.findQueue(status, targetType, fixed).map(report -> toResponse(report, true));
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
    private Long requireTargetAuthor(Account reporter, ReportTargetType targetType, Long targetId) {
        switch (targetType) {
            case POST:
                return postRepo.findById(targetId)
                        .map(post -> post.getBranding().getAccount().getId())
                        .orElseThrow(ResourceNotFoundException::new);
            case COMMENT:
                return commentRepo.findById(targetId)
                        .map(comment -> comment.getAccount().getId())
                        .orElseThrow(ResourceNotFoundException::new);
            case COURSE:
                return courseRepo.findById(targetId)
                        .map(course -> course.getInstructor().getId())
                        .orElseThrow(ResourceNotFoundException::new);
            case CHAT_MESSAGE:
                // 🔴 방 접근 권한을 채팅 도메인이 직접 본다 — 메시지 id 만으로 신고를 받으면 남의 방
                // 메시지를 id 를 올려가며 신고할 수 있고, 어드민 큐의 미리보기가 대화를 읽는 채널이 된다.
                return chatMessageService.requireReportableSender(reporter, targetId);
            default:
                throw new BadRequestException("신고할 수 없는 대상이에요.");
        }
    }

    /**
     * 조치 = 대상을 실제로 안 보이게 만든다. 상태만 바꾸고 콘텐츠가 남아 있으면 조치가 아니다.
     *
     * <p>댓글은 <b>유저 삭제와 같은 규칙</b>을 타야 한다(대댓글 있으면 자리 남김, 없으면 완전 삭제) —
     * 그래서 문구를 여기서 다시 쓰지 않고 {@link CommunityCommentService#deleteByModerator} 에 맡긴다.
     * 예전에는 여기서 무조건 soft delete + 문자열 리터럴을 직접 박아, 대댓글 없는 댓글이 어드민 조치
     * 뒤에만 껍데기로 남고 문구도 두 곳에서 갈릴 수 있었다.
     */
    private void hideTarget(ContentReport report) {
        Long targetId = report.getTargetId();
        switch (report.getTargetType()) {
            case POST:
                // 숨김과 함께 조치 표식을 남긴다 — 작성자가 토글로 되살리지 못하게.
                // 커뮤니티는 이 컬럼만 보고 판단한다(신고 테이블을 읽지 않는다 — 순환 의존 회피).
                postRepo.findById(targetId).ifPresent(post -> {
                    post.setHidden(true);
                    post.setModeratedAt(OffsetDateTime.now(ZoneOffset.UTC));
                });
                return;
            case COMMENT:
                commentService.deleteByModerator(targetId);
                return;
            case COURSE:
                // 둘러보기·상세·강의 수·연결 카드에서 빠지고 신규 신청이 막힌다.
                // 이미 확정·결제된 수강은 건드리지 않는다(Course.blockedAt Javadoc).
                courseRepo.findById(targetId)
                        .ifPresent(course -> course.setBlockedAt(OffsetDateTime.now(ZoneOffset.UTC)));
                return;
            case CHAT_MESSAGE:
                chatMessageService.deleteByModerator(targetId);
                return;
            default:
                throw new BadRequestException("조치할 수 없는 대상이에요.");
        }
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
                .targetAuthorNickName(forAdmin ? targetAuthorNickNameOf(report) : null)
                .build();
    }

    /** 대상 본문 앞부분. 이미 지워졌으면 null — 어드민 화면이 "대상 없음" 으로 렌더한다. */
    private String previewOf(ContentReport report) {
        Long targetId = report.getTargetId();
        String body;
        switch (report.getTargetType()) {
            case POST:
                body = postRepo.findById(targetId).map(this::postPreview).orElse(null);
                break;
            case COMMENT:
                body = commentRepo.findById(targetId).map(CommunityComment::getBody).orElse(null);
                break;
            case COURSE:
                body = courseRepo.findById(targetId).map(Course::getTitle).orElse(null);
                break;
            case CHAT_MESSAGE:
                body = chatMessageService.moderationPreview(targetId);
                break;
            default:
                body = null;
        }
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH);
    }

    /**
     * 조치 대상의 작성자 닉네임 — 어드민 큐에만 싣는다. 대상이 이미 지워졌으면 {@code null}.
     *
     * <p>접수 때 쓰는 {@link #requireTargetAuthor} 와 달리 <b>던지지 않는다</b>. 큐는 이미 접수된 행을
     * 훑는 화면이라, 대상이 사라졌다고 목록 전체가 500 이 되면 안 된다.
     */
    private String targetAuthorNickNameOf(ContentReport report) {
        Long targetId = report.getTargetId();
        Long authorId;
        switch (report.getTargetType()) {
            case POST:
                authorId = postRepo.findById(targetId)
                        .map(post -> post.getBranding().getAccount().getId()).orElse(null);
                break;
            case COMMENT:
                authorId = commentRepo.findById(targetId)
                        .map(comment -> comment.getAccount().getId()).orElse(null);
                break;
            case COURSE:
                authorId = courseRepo.findById(targetId)
                        .map(course -> course.getInstructor().getId()).orElse(null);
                break;
            case CHAT_MESSAGE:
                authorId = chatMessageService.moderationSenderId(targetId);
                break;
            default:
                authorId = null;
        }
        return authorId == null ? null
                : accountRepo.findById(authorId).map(Account::getNickName).orElse(null);
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
