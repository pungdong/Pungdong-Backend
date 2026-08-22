package com.diving.pungdong.certificate;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineJpaRepo;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 강사 자격 검증 트랙의 <b>상태 규칙 3개</b> — 모든 전이가 여기서만 일어난다
 * (정책: docs/features/instructor-onboarding.md §자격증 검증).
 *
 * <ul>
 *   <li><b>Rule A (자격증 쓰기)</b> — 강사레벨 자격증을 <i>승인된 종목</i>에 등록하거나 식별필드(종목·단체·레벨·번호·사진)를
 *       고치면 PENDING(이전이 NONE/REJECTED 면 ADDITIONAL, VERIFIED 면 RE_VERIFY). 승인 안 된 종목이면 NONE.
 *       기록 필드(취득일·발급기관·강의연결·표시명)는 상태 불변. 백필 행의 번호 null → 값 도 기록 보완(불변).</li>
 *   <li><b>Rule B (신청 이벤트)</b> — 제출: 첨부 자격증 전부 PENDING(APPLICATION) + 그 종목의 NONE 강사레벨 자동 첨부.
 *       승인: VERIFIED + 심사 중 새로 올라온 NONE 도 PENDING(ADDITIONAL) sweep. 반려: REJECTED + 사유.</li>
 *   <li><b>Rule C (가드)</b> — 승인 ∧ 자격증 필수 종목에서 {VERIFIED, PENDING} 이 0 이 되는 쓰기(삭제·하향·종목변경)는
 *       400. 심사 중인 신청이 참조하는 자격증의 삭제·종목변경·하향도 400. 문구는 FE 가 그대로 띄운다.</li>
 * </ul>
 *
 * <p>인정한 구멍: 마지막 VERIFIED 를 RE_VERIFY 로 올렸다가 반려되면 종목에 검증 자격증 0 + INSTRUCTOR 권한 유지.
 * 자동 회수 없음 — 사용자가 REJECTED 사유를 보고 고치면 Rule A 로 다시 PENDING.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CertificateVerificationService {

    static final List<CertLevel> INSTRUCTOR_LEVELS = List.of(CertLevel.INSTRUCTOR, CertLevel.INSTRUCTOR_TRAINER);
    static final List<CertificateVerificationStatus> LIVE =
            List.of(CertificateVerificationStatus.VERIFIED, CertificateVerificationStatus.PENDING);

    public static final String MSG_LAST_VERIFIED = "이 종목의 마지막 검증 자격증이에요. 다른 자격증을 먼저 등록해주세요.";
    public static final String MSG_UNDER_REVIEW_DELETE = "심사 중인 자격증은 삭제할 수 없어요.";
    public static final String MSG_UNDER_REVIEW_CHANGE = "심사 중인 자격증은 종목이나 자격 등급을 바꿀 수 없어요.";
    public static final String MSG_NOT_MINE = "등록되지 않은 자격증이 있어요. 내 자격증을 확인해주세요.";
    public static final String MSG_DISCIPLINE_MISMATCH = "자격증의 종목이 신청 종목과 달라요.";
    public static final String MSG_NOT_INSTRUCTOR_LEVEL = "강사 레벨 자격증만 강사 신청에 쓸 수 있어요.";

    private final StudentCertificateJpaRepo certificateRepo;
    private final CertificateReviewJpaRepo reviewRepo;
    private final InstructorApprovalLookup approvalLookup;
    private final DisciplineJpaRepo disciplineRepo;

    /** 수정 전 식별필드 스냅샷 — 변경 판정 + RE_VERIFY 의 previous. */
    @Getter
    @AllArgsConstructor
    public static class IdentitySnapshot {
        private final String disciplineCode;
        private final String organizationCode;
        private final CertLevel level;
        private final String certificateNumber;

        public static IdentitySnapshot of(StudentCertificate c) {
            return new IdentitySnapshot(c.getDisciplineCode(), c.getOrganizationCode(), c.getLevel(), c.getCertificateNumber());
        }
    }

    /* ═══════════ Rule A ═══════════ */

    /** 등록 직후. 수강생 레벨은 건드리지 않는다(NONE). */
    public void onRegistered(StudentCertificate cert) {
        if (cert.isInstructorLevel() && approved(cert)) {
            pendingAdditional(cert, now());
        }
    }

    /**
     * 수정 직후 — {@code before} 와 비교해 식별필드가 바뀌었을 때만 전이한다.
     * {@code photoChanged} 는 사진 교체(식별필드 취급) 여부를 호출처가 넘긴다.
     */
    public void onUpdated(StudentCertificate cert, IdentitySnapshot before, boolean photoChanged) {
        OffsetDateTime now = now();
        CertificateVerification v = cert.getVerification();

        if (!cert.isInstructorLevel()) {
            // 강사레벨 미만으로 내려감(Rule C 를 통과했으니 마지막 한 장이 아니다) → 검증 트랙 이탈.
            if (!v.is(CertificateVerificationStatus.NONE)) {
                cancelPendingReview(cert.getId());
                cert.clearVerification();
            }
            return;
        }

        boolean identityChanged = photoChanged
                || !Objects.equals(before.getDisciplineCode(), cert.getDisciplineCode())
                || !Objects.equals(before.getOrganizationCode(), cert.getOrganizationCode())
                || before.getLevel() != cert.getLevel()
                // 백필 행의 null → 값 은 기록 보완이다(예외). 값 → 다른 값 만 식별 변경.
                || (before.getCertificateNumber() != null
                    && !Objects.equals(before.getCertificateNumber(), cert.getCertificateNumber()));
        if (!identityChanged) {
            return;
        }

        boolean approvedNow = approved(cert);
        switch (v.getStatus()) {
            case PENDING:
                if (v.getKind() == CertificateVerificationKind.APPLICATION) {
                    return; // 신청과 함께 심사 중 — 어드민이 현재 값을 본다(종목변경·하향은 Rule C 가 이미 막았다).
                }
                if (approvedNow) {
                    // 이미 큐에 있다. 종목이 바뀌었으면 큐 행의 종목만 따라간다(previous 는 최초 스냅샷 유지).
                    reviewRepo.findFirstByCertificateIdAndStatus(cert.getId(), CertificateReviewStatus.PENDING)
                            .ifPresent(r -> r.moveToDiscipline(cert.getDisciplineCode()));
                } else {
                    cancelPendingReview(cert.getId());
                    cert.clearVerification();
                }
                return;
            case VERIFIED:
                if (approvedNow) {
                    cert.markPending(CertificateVerificationKind.RE_VERIFY, now);
                    reviewRepo.save(CertificateReview.builder()
                            .kind(CertificateReviewKind.RE_VERIFY)
                            .certificateId(cert.getId())
                            .accountId(cert.getOwner().getId())
                            .disciplineCode(cert.getDisciplineCode())
                            .status(CertificateReviewStatus.PENDING)
                            .previousDisciplineCode(before.getDisciplineCode())
                            .previousOrganizationCode(before.getOrganizationCode())
                            .previousLevel(before.getLevel())
                            .previousCertificateNumber(before.getCertificateNumber())
                            .requestedAt(now)
                            .build());
                } else {
                    cert.clearVerification(); // 승인 안 된 종목으로 옮김 — 마크는 그 종목에서 의미가 없다
                }
                return;
            case NONE:
            case REJECTED:
            default:
                if (approvedNow) {
                    pendingAdditional(cert, now);
                } else {
                    cert.clearVerification();
                }
        }
    }

    /* ═══════════ Rule C ═══════════ */

    /** 수정 전 가드 — 종목 변경·강사레벨 미만 하향만 본다(다른 필드는 Rule A 영역). */
    public void guardUpdate(StudentCertificate cert, String newDisciplineCode, CertLevel newLevel) {
        boolean disciplineChange = !Objects.equals(cert.getDisciplineCode(), newDisciplineCode);
        boolean downgrade = cert.isInstructorLevel() && (newLevel == null || !newLevel.isInstructorLevel());
        if (!disciplineChange && !downgrade) {
            return;
        }
        if (cert.getVerification().isUnderApplicationReview()) {
            throw new BadRequestException(MSG_UNDER_REVIEW_CHANGE);
        }
        if (isLastLive(cert)) {
            throw new BadRequestException(MSG_LAST_VERIFIED);
        }
    }

    /** 삭제 전 가드. */
    public void guardDelete(StudentCertificate cert) {
        if (cert.getVerification().isUnderApplicationReview()) {
            throw new BadRequestException(MSG_UNDER_REVIEW_DELETE);
        }
        if (isLastLive(cert)) {
            throw new BadRequestException(MSG_LAST_VERIFIED);
        }
    }

    /** 삭제 직후 — 그 자격증의 검수 행(대기·이력)을 함께 지운다(FK 없음). */
    public void onDeleted(Long certificateId) {
        reviewRepo.deleteByCertificateId(certificateId);
    }

    /* ═══════════ Rule B — instructorapplication 이 호출 ═══════════ */

    /**
     * 제출/재제출: 요청 id 를 검증(소유·종목·강사레벨)하고, 그 종목의 NONE 강사레벨 자격증을 <b>자동 첨부</b>해
     * 전부 PENDING(APPLICATION) 으로 올린 뒤 NEW 검수 행을 만든다. 이전 제출에서 빠진 자격증은 NONE 으로 돌린다.
     *
     * @return 최종 첨부 id (요청 순서 → 자동 첨부 순). 호출처가 신청에 저장한다.
     */
    public List<Long> attachToApplication(Long applicationId, Long ownerId, String disciplineCode,
                                          List<Long> requestedIds, List<Long> previouslyAttached) {
        OffsetDateTime now = now();
        List<Long> ids = requestedIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(requestedIds));

        Map<Long, StudentCertificate> owned = certificateRepo.findByIdInAndOwnerId(ids, ownerId).stream()
                .collect(Collectors.toMap(StudentCertificate::getId, Function.identity()));
        if (owned.size() != ids.size()) {
            throw new BadRequestException(MSG_NOT_MINE); // 없음/남의 것 통일 — 존재 숨김
        }
        for (StudentCertificate c : owned.values()) {
            if (!Objects.equals(c.getDisciplineCode(), disciplineCode)) {
                throw new BadRequestException(MSG_DISCIPLINE_MISMATCH);
            }
            if (!c.isInstructorLevel()) {
                throw new BadRequestException(MSG_NOT_INSTRUCTOR_LEVEL);
            }
        }

        // 자동 첨부 — 신청 전에 미리 올려둔 강사레벨 자격증. 어드민이 한 번에 다 보고 한 번에 승인한다.
        List<StudentCertificate> auto = certificateRepo
                .findByOwnerDisciplineLevelsAndStatus(ownerId, disciplineCode, INSTRUCTOR_LEVELS,
                        CertificateVerificationStatus.NONE).stream()
                .filter(c -> !owned.containsKey(c.getId()))
                .collect(Collectors.toList());

        List<Long> attached = new ArrayList<>(ids);
        auto.forEach(c -> attached.add(c.getId()));
        Set<Long> attachedSet = new HashSet<>(attached);

        // 재제출에서 빠진 것 — 신청에 묶여 있던 상태(APPLICATION)만 풀어준다.
        if (previouslyAttached != null) {
            for (Long prev : previouslyAttached) {
                if (attachedSet.contains(prev)) {
                    continue;
                }
                certificateRepo.findById(prev)
                        .filter(c -> c.getVerification().getKind() == CertificateVerificationKind.APPLICATION)
                        .ifPresent(StudentCertificate::clearVerification);
            }
        }

        owned.values().forEach(c -> c.markPending(CertificateVerificationKind.APPLICATION, now));
        auto.forEach(c -> c.markPending(CertificateVerificationKind.APPLICATION, now));

        reviewRepo.findFirstByApplicationIdAndStatus(applicationId, CertificateReviewStatus.PENDING)
                .ifPresent(reviewRepo::delete); // 방어 — 재제출은 REJECTED 에서만 오므로 보통 없다
        reviewRepo.save(CertificateReview.builder()
                .kind(CertificateReviewKind.NEW)
                .applicationId(applicationId)
                .accountId(ownerId)
                .disciplineCode(disciplineCode)
                .status(CertificateReviewStatus.PENDING)
                .requestedAt(now)
                .build());
        return attached;
    }

    /** 승인: 첨부 전부 VERIFIED + NEW 행 APPROVED + 심사 중 새로 올라온 NONE 강사레벨은 PENDING(ADDITIONAL) sweep. */
    public void onApplicationApproved(Long applicationId, Long ownerId, String disciplineCode,
                                      List<Long> attachedIds, Long reviewerId) {
        OffsetDateTime now = now();
        certificateRepo.findAllById(attachedIds == null ? List.of() : attachedIds)
                .forEach(c -> c.markVerified(now));
        reviewRepo.findFirstByApplicationIdAndStatus(applicationId, CertificateReviewStatus.PENDING)
                .ifPresent(r -> r.approve(reviewerId, now));
        certificateRepo.findByOwnerDisciplineLevelsAndStatus(ownerId, disciplineCode, INSTRUCTOR_LEVELS,
                        CertificateVerificationStatus.NONE)
                .forEach(c -> pendingAdditional(c, now));
    }

    /** 반려: 첨부 전부 REJECTED(사유 복사) + NEW 행 REJECTED. */
    public void onApplicationRejected(Long applicationId, List<Long> attachedIds, String reason, Long reviewerId) {
        OffsetDateTime now = now();
        certificateRepo.findAllById(attachedIds == null ? List.of() : attachedIds)
                .forEach(c -> c.markRejected(reason, now));
        reviewRepo.findFirstByApplicationIdAndStatus(applicationId, CertificateReviewStatus.PENDING)
                .ifPresent(r -> r.reject(reviewerId, reason, now));
    }

    /* ═══════════ 내부 ═══════════ */

    private void pendingAdditional(StudentCertificate cert, OffsetDateTime now) {
        cert.markPending(CertificateVerificationKind.ADDITIONAL, now);
        if (reviewRepo.findFirstByCertificateIdAndStatus(cert.getId(), CertificateReviewStatus.PENDING).isEmpty()) {
            reviewRepo.save(CertificateReview.builder()
                    .kind(CertificateReviewKind.ADDITIONAL)
                    .certificateId(cert.getId())
                    .accountId(cert.getOwner().getId())
                    .disciplineCode(cert.getDisciplineCode())
                    .status(CertificateReviewStatus.PENDING)
                    .requestedAt(now)
                    .build());
        }
    }

    private void cancelPendingReview(Long certificateId) {
        reviewRepo.findFirstByCertificateIdAndStatus(certificateId, CertificateReviewStatus.PENDING)
                .ifPresent(reviewRepo::delete);
    }

    private boolean approved(StudentCertificate cert) {
        return approvalLookup.isApprovedInstructor(cert.getOwner().getId(), cert.getDisciplineCode());
    }

    /**
     * 이 자격증이 그 종목의 <b>마지막 살아있는 검증</b>인가 — 승인 ∧ 자격증 필수 종목 ∧ 이 행이 VERIFIED/PENDING ∧
     * 같은 조건의 행이 이것뿐. 자격증 불필요 종목(수영/서핑)은 강사 정의가 자격증이 아니라 해당 없음.
     */
    private boolean isLastLive(StudentCertificate cert) {
        if (!cert.isInstructorLevel() || !cert.getVerification().countsAsVerifiedOrPending()) {
            return false;
        }
        if (!approved(cert)) {
            return false;
        }
        boolean requiresCertification = disciplineRepo.findByCode(cert.getDisciplineCode())
                .map(Discipline::isRequiresCertification)
                .orElse(true);
        if (!requiresCertification) {
            return false;
        }
        return certificateRepo.countLive(cert.getOwner().getId(), cert.getDisciplineCode(), INSTRUCTOR_LEVELS, LIVE) <= 1;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
