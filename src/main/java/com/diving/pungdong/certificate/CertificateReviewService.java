package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.certificate.dto.AdminCertificateView;
import com.diving.pungdong.certificate.dto.ApplicationReviewView;
import com.diving.pungdong.certificate.dto.CertificateReviewCounts;
import com.diving.pungdong.certificate.dto.CertificateReviewDetail;
import com.diving.pungdong.certificate.dto.CertificateReviewPrevious;
import com.diving.pungdong.certificate.dto.CertificateReviewSummary;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineJpaRepo;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 어드민 검수 큐 — {@code certificate_review} 한 테이블 위의 목록/건수/상세/승인/반려.
 *
 * <p>세 종류가 한 큐에 있지만 승인의 <b>실체</b>는 다르다: NEW 는 강사 신청 승인(권한 부여 포함)이라
 * {@link InstructorApplicationReviewPort} 로 instructorapplication 에 위임하고, 그쪽이 Rule B 로 돌아와 이 행을
 * 닫는다. ADDITIONAL/RE_VERIFY 는 자격증 1장의 VERIFIED/REJECTED 전이라 여기서 직접 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertificateReviewService {

    public static final String MSG_NOT_PENDING = "이미 처리된 검수 요청이에요.";

    private final CertificateReviewJpaRepo reviewRepo;
    private final StudentCertificateJpaRepo certificateRepo;
    private final StudentCertificateService certificateService;
    private final AccountJpaRepo accountRepo;
    private final DisciplineJpaRepo disciplineRepo;
    private final InstructorApprovalLookup approvalLookup;
    private final InstructorApplicationReviewPort applicationPort;

    /* ─── 목록 / 건수 ───────────────────────────────────────── */

    /** 정렬은 서버 고정(요청 최신순 + id tie-break) — {@code PageClamp} 가 클라이언트 정렬을 버리므로 여기서 붙인다. */
    public Page<CertificateReviewSummary> getReviews(CertificateReviewStatus status, Pageable clamped) {
        Pageable pageable = PageRequest.of(clamped.getPageNumber(), clamped.getPageSize(),
                Sort.by(Sort.Direction.DESC, "requestedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<CertificateReview> page = status == null
                ? reviewRepo.findAll(pageable)
                : reviewRepo.findAllByStatus(status, pageable);
        List<CertificateReview> rows = page.getContent();
        if (rows.isEmpty()) {
            return page.map(r -> null);
        }

        Set<Long> accountIds = rows.stream().map(CertificateReview::getAccountId).collect(Collectors.toSet());
        Map<Long, Account> accounts = accountRepo.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        // 단체 칩 — NEW 는 신청의 첨부 전부, 나머지는 그 한 장. 페이지 단위로 한 번에.
        List<Long> applicationIds = rows.stream().map(CertificateReview::getApplicationId)
                .filter(id -> id != null).collect(Collectors.toList());
        Map<Long, List<Long>> attached = applicationIds.isEmpty() ? Map.of()
                : applicationPort.certificateIdsOf(applicationIds);
        Set<Long> certIds = new HashSet<>();
        attached.values().forEach(certIds::addAll);
        rows.stream().map(CertificateReview::getCertificateId).filter(id -> id != null).forEach(certIds::add);
        Map<Long, StudentCertificate> certs = certIds.isEmpty() ? Map.of()
                : certificateRepo.findAllById(certIds).stream()
                        .collect(Collectors.toMap(StudentCertificate::getId, Function.identity()));

        MissingFlag missing = missingFlag(accountIds);

        return page.map(r -> {
            Account account = accounts.get(r.getAccountId());
            List<Long> targetIds = r.getKind() == CertificateReviewKind.NEW
                    ? attached.getOrDefault(r.getApplicationId(), List.of())
                    : List.of(r.getCertificateId());
            List<String> orgCodes = targetIds.stream().map(certs::get)
                    .filter(c -> c != null && c.getOrganizationCode() != null)
                    .map(StudentCertificate::getOrganizationCode).distinct().collect(Collectors.toList());
            return CertificateReviewSummary.builder()
                    .reviewId(r.getId())
                    .kind(r.getKind())
                    .applicationId(r.getApplicationId())
                    .certificateId(r.getCertificateId())
                    .accountId(r.getAccountId())
                    .nickName(account == null ? null : account.getNickName())
                    .email(account == null ? null : account.getEmail())
                    .disciplineCode(r.getDisciplineCode())
                    .organizationCodes(orgCodes)
                    .status(r.getStatus())
                    .requestedAt(r.getRequestedAt())
                    .reviewedAt(r.getReviewedAt())
                    .verifiedCertificateMissing(missing.test(r.getAccountId(), r.getDisciplineCode()))
                    .build();
        });
    }

    public CertificateReviewCounts getCounts() {
        long pending = reviewRepo.countByStatus(CertificateReviewStatus.PENDING);
        long approved = reviewRepo.countByStatus(CertificateReviewStatus.APPROVED);
        long rejected = reviewRepo.countByStatus(CertificateReviewStatus.REJECTED);
        return CertificateReviewCounts.builder()
                .pending(pending).approved(approved).rejected(rejected)
                .total(pending + approved + rejected)
                .build();
    }

    /* ─── 상세 ──────────────────────────────────────────────── */

    public CertificateReviewDetail getDetail(Long reviewId) {
        CertificateReview r = reviewRepo.findById(reviewId).orElseThrow(ResourceNotFoundException::new);
        Account account = accountRepo.findById(r.getAccountId()).orElse(null);
        Account reviewer = r.getReviewerId() == null ? null : accountRepo.findById(r.getReviewerId()).orElse(null);

        ApplicationReviewView application = null;
        List<AdminCertificateView> certificates;
        if (r.getKind() == CertificateReviewKind.NEW) {
            application = applicationPort.view(r.getApplicationId()).orElse(null);
            certificates = certificateService.adminViewsOf(
                    application == null ? List.of() : application.getCertificateIds());
        } else {
            certificates = certificateService.adminViewsOf(List.of(r.getCertificateId()));
        }

        return CertificateReviewDetail.builder()
                .reviewId(r.getId())
                .kind(r.getKind())
                .status(r.getStatus())
                .disciplineCode(r.getDisciplineCode())
                .accountId(r.getAccountId())
                .nickName(account == null ? null : account.getNickName())
                .email(account == null ? null : account.getEmail())
                .reason(r.getReason())
                .requestedAt(r.getRequestedAt())
                .reviewedAt(r.getReviewedAt())
                .reviewerNickName(reviewer == null ? null : reviewer.getNickName())
                .verifiedCertificateMissing(missingFlag(List.of(r.getAccountId())).test(r.getAccountId(), r.getDisciplineCode()))
                .application(application)
                .certificates(certificates)
                .previous(r.getKind() == CertificateReviewKind.RE_VERIFY && r.hasPrevious()
                        ? CertificateReviewPrevious.of(r) : null)
                .build();
    }

    /* ─── 승인 / 반려 ───────────────────────────────────────── */

    @Transactional
    public void approve(Long reviewId, Account reviewer) {
        CertificateReview r = requirePending(reviewId);
        if (r.getKind() == CertificateReviewKind.NEW) {
            applicationPort.approve(r.getApplicationId(), reviewer); // Rule B 가 돌아와 이 행을 APPROVED 로 닫는다
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        StudentCertificate cert = certificateRepo.findById(r.getCertificateId())
                .orElseThrow(ResourceNotFoundException::new);
        cert.markVerified(now);
        r.approve(reviewer.getId(), now);
    }

    @Transactional
    public void reject(Long reviewId, Account reviewer, String reason) {
        CertificateReview r = requirePending(reviewId);
        if (r.getKind() == CertificateReviewKind.NEW) {
            applicationPort.reject(r.getApplicationId(), reviewer, reason);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        StudentCertificate cert = certificateRepo.findById(r.getCertificateId())
                .orElseThrow(ResourceNotFoundException::new);
        cert.markRejected(reason, now);
        r.reject(reviewer.getId(), reason, now);
    }

    /* ─── 내부 ──────────────────────────────────────────────── */

    private CertificateReview requirePending(Long reviewId) {
        CertificateReview r = reviewRepo.findById(reviewId).orElseThrow(ResourceNotFoundException::new);
        if (r.getStatus() != CertificateReviewStatus.PENDING) {
            throw new BadRequestException(MSG_NOT_PENDING);
        }
        return r;
    }

    /**
     * "검증 자격증 0건" 판정기 — 승인 ∧ 자격증 필수 종목 ∧ 살아있는({VERIFIED, PENDING}) 강사레벨 자격증 0.
     * 계정 묶음 단위로 한 번에 읽어 두고 행마다 {@code test} 만 한다.
     */
    private MissingFlag missingFlag(Collection<Long> accountIds) {
        Map<Long, Set<String>> approved = approvalLookup.approvedDisciplinesOf(accountIds);
        Map<String, Long> live = new HashMap<>();
        for (Object[] row : certificateRepo.countLiveByAccountIds(accountIds,
                CertificateVerificationService.INSTRUCTOR_LEVELS, CertificateVerificationService.LIVE)) {
            live.put(row[0] + ":" + row[1], ((Number) row[2]).longValue());
        }
        Map<String, Boolean> requires = new HashMap<>();
        return (accountId, disciplineCode) -> {
            if (!approved.getOrDefault(accountId, Set.of()).contains(disciplineCode)) {
                return false;
            }
            boolean required = requires.computeIfAbsent(disciplineCode, code -> disciplineRepo.findByCode(code)
                    .map(Discipline::isRequiresCertification).orElse(true));
            return required && live.getOrDefault(accountId + ":" + disciplineCode, 0L) == 0L;
        };
    }

    @FunctionalInterface
    private interface MissingFlag {
        boolean test(Long accountId, String disciplineCode);
    }
}
