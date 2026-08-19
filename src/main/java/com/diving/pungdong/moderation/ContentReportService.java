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
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** 어드민 큐가 "신고자가 그 강의를 신청한 사람인가" 를 보기 위한 단방향 읽기(§큐 맥락). */
    private final EnrollmentJpaRepo enrollmentRepo;
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
                // 조치 대상을 지금 고정한다 — 대상이 지워진 뒤에는 되찾을 수 없고, 그러면 이 행은
                // "누구에 대한 신고인지 모르는 행" 이 된다(반복 신고 집계도 그때 함께 무너진다).
                .targetAuthorAccountId(authorId)
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

    /**
     * 큐 목록. {@code status}·{@code targetType}·{@code targetAuthorNickName} 셋 다 생략 가능
     * (생략 = 그 축 전체). 최신 접수순.
     *
     * <p>{@code targetAuthorNickName} 은 <b>같은 사람에 대한 신고만 모아 보는</b> 축이다 — 대상이 넷으로
     * 흩어져 있어 같은 강사의 여러 강의에 걸친 반복 신고가 큐에서 서로 만나지 못했다. 없는 닉네임이면
     * 빈 페이지다(400 이 아니다 — 어드민이 오타를 냈다고 화면이 깨질 이유가 없다).
     */
    public Page<ContentReportResponse> queue(ReportStatus status, ReportTargetType targetType,
                                             String targetAuthorNickName, Pageable pageable) {
        // 피드와 같은 규칙 — 클라이언트 정렬을 버리고 크기 상한을 건다. 어드민이라고 열어두면
        // ?size=100000 한 방에 신고 전량이 미리보기까지 붙어 나온다.
        Pageable fixed = PageClamp.fixed(pageable);

        Long authorId = null;
        if (StringUtils.hasText(targetAuthorNickName)) {
            authorId = accountRepo.findByNickName(targetAuthorNickName).map(Account::getId).orElse(null);
            if (authorId == null) {
                return Page.empty(fixed);
            }
        }

        Page<ContentReport> page = reportRepo.findQueue(status, targetType, authorId, fixed);
        QueueContext context = queueContext(page.getContent());
        return page.map(report -> toResponse(report, true, context));
    }

    /**
     * 한 페이지분 어드민 맥락을 <b>배치로</b> 모은다 — 작성자 닉네임 · 작성자별 누적 신고 수 ·
     * (강의 신고 한정) 신고자의 그 강의 신청 이력.
     *
     * <p>행마다 조회하면 페이지 크기만큼 쿼리가 곱해진다. 이 셋은 전부 id 집합만 있으면 한 번에
     * 물어볼 수 있는 값이라 목록 조회 앞에서 모아 둔다.
     */
    private QueueContext queueContext(List<ContentReport> reports) {
        Set<Long> authorIds = reports.stream()
                .map(ContentReport::getTargetAuthorAccountId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> nickNames = authorIds.isEmpty() ? Map.of()
                : accountRepo.findAllById(authorIds).stream()
                        .filter(account -> account.getNickName() != null)
                        .collect(Collectors.toMap(Account::getId, Account::getNickName));

        Map<Long, Long> reportCounts = authorIds.isEmpty() ? Map.of() : countsByAuthor(authorIds);

        // 강의 신고에서만 의미가 있다 — 신고자가 그 강의를 신청한 적 있는지가 "경쟁자의 악의적 신고"
        // 와 "실제 수강생의 분쟁" 을 가르는 유일한 신호다(나머지 수강 맥락은 별도 상세 화면의 몫).
        Set<Long> reporterIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        for (ContentReport report : reports) {
            if (report.getTargetType() == ReportTargetType.COURSE) {
                reporterIds.add(report.getReporter().getId());
                courseIds.add(report.getTargetId());
            }
        }
        Set<String> enrolledPairs = reporterIds.isEmpty() ? Set.of()
                : enrollmentRepo.findStudentCoursePairs(reporterIds, courseIds).stream()
                        .map(row -> pairKey((Long) row[0], (Long) row[1]))
                        .collect(Collectors.toSet());

        return new QueueContext(nickNames, reportCounts, enrolledPairs);
    }

    private Map<Long, Long> countsByAuthor(Collection<Long> authorIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : reportRepo.countByTargetAuthorIn(authorIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static String pairKey(Long studentId, Long courseId) {
        return studentId + ":" + courseId;
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
    public ContentReportResponse handle(Long reportId, ReportStatus decision, String note) {
        if (decision != ReportStatus.ACTIONED && decision != ReportStatus.DISMISSED) {
            throw new BadRequestException("처리 결과는 조치 또는 기각이어야 해요.");
        }
        ContentReport report = reportRepo.findById(reportId).orElseThrow(ResourceNotFoundException::new);

        if (decision == ReportStatus.ACTIONED) {
            hideTarget(report);
        }
        report.setStatus(decision);
        report.setHandledAt(OffsetDateTime.now(ZoneOffset.UTC));
        // 판단 근거는 처리 시점에만 존재한다 — 기각이 "문제없음" 과 "따로 경고함" 을 같은 행으로
        // 보이게 하는 걸 이 한 칸이 막는다. 빈 값이면 기존 메모를 지우지 않는다(재처리 시 유실 방지).
        if (StringUtils.hasText(note)) {
            report.setAdminNote(note);
        }
        return toResponse(report, true, queueContext(List.of(report)));
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
        return toResponse(report, forAdmin, QueueContext.EMPTY);
    }

    private ContentReportResponse toResponse(ContentReport report, boolean forAdmin, QueueContext context) {
        Long authorId = report.getTargetAuthorAccountId();
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
                .targetAuthorNickName(forAdmin ? targetAuthorNickNameOf(report, context) : null)
                .targetAuthorReportCount(forAdmin && authorId != null
                        ? context.reportCounts().getOrDefault(authorId, 1L) : null)
                .reporterEnrolled(forAdmin && report.getTargetType() == ReportTargetType.COURSE
                        ? context.enrolledPairs().contains(
                                pairKey(report.getReporter().getId(), report.getTargetId()))
                        : null)
                .adminNote(forAdmin ? report.getAdminNote() : null)
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
     * 조치 대상의 작성자 닉네임 — 어드민 큐에만 싣는다.
     *
     * <p><b>접수 때 고정한 {@code targetAuthorAccountId} 가 1순위다.</b> 대상이 그 사이 지워져도 누구에
     * 대한 신고였는지가 남는다. 그 값이 없는 건 V34 이전에 접수됐고 백필도 못 한 행(= 대상이 이미
     * 사라진 행)뿐이라, 그때만 예전처럼 대상을 열어본다.
     *
     * <p>접수 때 쓰는 {@link #requireTargetAuthor} 와 달리 <b>던지지 않는다</b>. 큐는 이미 접수된 행을
     * 훑는 화면이라, 대상이 사라졌다고 목록 전체가 500 이 되면 안 된다.
     */
    private String targetAuthorNickNameOf(ContentReport report, QueueContext context) {
        Long fixedAuthorId = report.getTargetAuthorAccountId();
        if (fixedAuthorId != null) {
            String cached = context.nickNames().get(fixedAuthorId);
            return cached != null ? cached
                    : accountRepo.findById(fixedAuthorId).map(Account::getNickName).orElse(null);
        }
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

    /**
     * 한 페이지분 어드민 맥락(배치 조회 결과). 행 단위로 다시 DB 를 때리지 않기 위한 캐리어다.
     *
     * @param nickNames    작성자 id → 닉네임
     * @param reportCounts 작성자 id → 누적 신고 수(상태·대상 종류 무관)
     * @param enrolledPairs {@code "학생id:강의id"} — 신청 이력이 있는 쌍만 담긴다
     */
    private record QueueContext(Map<Long, String> nickNames,
                                Map<Long, Long> reportCounts,
                                Set<String> enrolledPairs) {

        static final QueueContext EMPTY = new QueueContext(Map.of(), Map.of(), Set.of());
    }
}
