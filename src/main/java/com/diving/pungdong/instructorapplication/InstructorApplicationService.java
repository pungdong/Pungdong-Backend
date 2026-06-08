package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.dto.*;
import com.diving.pungdong.instructorapplication.storage.CertificateImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 강사 신청 도메인 서비스 — 본인확인 → 자격증 업로드 → 제출/재제출 → 어드민 승인/반려.
 *
 * <p>상태머신 전이는 모두 여기서 강제한다. 컨트롤러는 입출력 매핑만 담당.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorApplicationService {

    static final String ORGANIZATION_OTHER = "OTHER";

    private final InstructorApplicationJpaRepo applicationRepo;
    private final IdentityVerificationJpaRepo identityVerificationRepo;
    private final AccountJpaRepo accountRepo;
    private final IdentityVerifier identityVerifier;
    private final CertificateImageStorage certificateImageStorage;

    /* ─── 본인확인 (stub 경계) ─────────────────────────────── */

    @Transactional
    public IdentityVerificationResult verifyIdentity(Account account, IdentityVerificationRequest request) {
        Account managed = loadAccount(account);
        IdentityVerification verification = identityVerifier.verify(managed, request);
        return IdentityVerificationResult.builder()
                .verificationId(verification.getId())
                .verified(true)
                .realName(verification.getRealName())
                .build();
    }

    /* ─── 자격증 이미지 업로드 (2-phase 1단계) ───────────────── */

    @Transactional
    public CertificateImageResult uploadCertificateImage(Account account, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException();
        }
        try {
            String url = certificateImageStorage.store(image, account.getEmail());
            return CertificateImageResult.builder().fileURL(url).build();
        } catch (IOException e) {
            throw new BadRequestException();
        }
    }

    /* ─── 신청 제출 / 재제출 ─────────────────────────────────── */

    @Transactional
    public InstructorApplicationResult submit(Account account, InstructorApplicationSubmitRequest request) {
        Account managed = loadAccount(account);
        IdentityVerification verification = resolveVerification(managed, request.getVerificationId());
        validateOrganization(request.getOrganizationCode(), request.getOrganizationOther());

        InstructorApplication application = applicationRepo.findByAccountId(managed.getId())
                .orElse(null);

        if (application == null) {
            application = InstructorApplication.builder()
                    .account(managed)
                    .createdAt(LocalDateTime.now())
                    .build();
        } else {
            switch (application.getStatus()) {
                case SUBMITTED:
                    throw new BadRequestException(); // 이미 심사 중 — 중복 신청 불가
                case APPROVED:
                    throw new BadRequestException(); // 이미 강사 — 재신청 불필요
                case REJECTED:
                    break;                            // 반려 건은 새 내용으로 재제출 허용
            }
        }

        applyFields(application, request, verification);
        InstructorApplication saved = applicationRepo.save(application);
        return InstructorApplicationResult.builder()
                .applicationId(saved.getId())
                .status(saved.getStatus())
                .build();
    }

    /**
     * 내 신청 수정·재제출 (PUT /me). 반려된 건을 고쳐 다시 SUBMITTED 로 되돌리거나, 심사 전
     * SUBMITTED 건의 내용을 갱신한다. 승인된 건은 수정 불가.
     */
    @Transactional
    public InstructorApplicationResult resubmit(Account account, InstructorApplicationSubmitRequest request) {
        Account managed = loadAccount(account);
        InstructorApplication application = applicationRepo.findByAccountId(managed.getId())
                .orElseThrow(BadRequestException::new); // 수정할 신청이 없음

        if (application.getStatus() == InstructorApplicationStatus.APPROVED) {
            throw new BadRequestException(); // 승인된 신청은 수정 불가
        }

        IdentityVerification verification = resolveVerification(managed, request.getVerificationId());
        validateOrganization(request.getOrganizationCode(), request.getOrganizationOther());

        applyFields(application, request, verification);
        InstructorApplication saved = applicationRepo.save(application);
        return InstructorApplicationResult.builder()
                .applicationId(saved.getId())
                .status(saved.getStatus())
                .build();
    }

    /* ─── 내 신청 조회 (프로필 탭) ───────────────────────────── */

    public MyInstructorApplicationResponse getMyApplication(Account account) {
        return applicationRepo.findByAccountId(account.getId())
                .map(this::toMyResponse)
                .orElseGet(MyInstructorApplicationResponse::none);
    }

    /* ─── 어드민 ─────────────────────────────────────────────── */

    public Page<InstructorApplicationSummary> getApplications(InstructorApplicationStatus status, Pageable pageable) {
        Page<InstructorApplication> page = (status == null)
                ? applicationRepo.findAll(pageable)               // "전체" 탭
                : applicationRepo.findAllByStatus(status, pageable);
        return page.map(this::toSummary);
    }

    /** 상태별 건수 (어드민 탭 뱃지). */
    public InstructorApplicationCounts getCounts() {
        long submitted = applicationRepo.countByStatus(InstructorApplicationStatus.SUBMITTED);
        long approved = applicationRepo.countByStatus(InstructorApplicationStatus.APPROVED);
        long rejected = applicationRepo.countByStatus(InstructorApplicationStatus.REJECTED);
        return InstructorApplicationCounts.builder()
                .submitted(submitted)
                .approved(approved)
                .rejected(rejected)
                .total(submitted + approved + rejected)
                .build();
    }

    public InstructorApplicationDetail getApplicationDetail(Long applicationId) {
        InstructorApplication application = applicationRepo.findById(applicationId)
                .orElseThrow(ResourceNotFoundException::new);
        return toDetail(application);
    }

    @Transactional
    public void approve(Long applicationId, Account reviewer) {
        InstructorApplication application = applicationRepo.findById(applicationId)
                .orElseThrow(ResourceNotFoundException::new);
        if (application.getStatus() != InstructorApplicationStatus.SUBMITTED) {
            throw new BadRequestException(); // 제출 상태만 승인 가능
        }

        application.setStatus(InstructorApplicationStatus.APPROVED);
        application.setReviewer(reviewer);
        application.setReviewedAt(LocalDateTime.now());
        application.setRejectionReason(null);
        applicationRepo.save(application);

        // 강사 권한 부여 — STUDENT 유지 + INSTRUCTOR 추가 (additive). 권한은 매 요청 DB 에서
        // 재계산되므로 토큰 재발급 없이 다음 요청부터 즉시 반영된다.
        Account applicant = application.getAccount();
        applicant.getRoles().add(Role.INSTRUCTOR);
        applicant.setIsCertified(true);
        accountRepo.save(applicant);
    }

    @Transactional
    public void reject(Long applicationId, Account reviewer, String reason) {
        InstructorApplication application = applicationRepo.findById(applicationId)
                .orElseThrow(ResourceNotFoundException::new);
        if (application.getStatus() != InstructorApplicationStatus.SUBMITTED) {
            throw new BadRequestException(); // 제출 상태만 반려 가능
        }

        application.setStatus(InstructorApplicationStatus.REJECTED);
        application.setReviewer(reviewer);
        application.setReviewedAt(LocalDateTime.now());
        application.setRejectionReason(reason);
        applicationRepo.save(application);
    }

    /* ─── 내부 헬퍼 ─────────────────────────────────────────── */

    private Account loadAccount(Account account) {
        return accountRepo.findById(account.getId())
                .orElseThrow(ResourceNotFoundException::new);
    }

    private IdentityVerification resolveVerification(Account account, Long verificationId) {
        IdentityVerification verification = identityVerificationRepo.findById(verificationId)
                .orElseThrow(BadRequestException::new);
        if (verification.getAccount() == null || !verification.getAccount().getId().equals(account.getId())) {
            throw new BadRequestException(); // 남의 본인확인을 참조할 수 없음
        }
        return verification;
    }

    private void validateOrganization(String organizationCode, String organizationOther) {
        if (ORGANIZATION_OTHER.equalsIgnoreCase(organizationCode)
                && (organizationOther == null || organizationOther.isBlank())) {
            throw new BadRequestException(); // 기타 단체는 직접입력 필수
        }
    }

    private void applyFields(InstructorApplication application, InstructorApplicationSubmitRequest request,
                             IdentityVerification verification) {
        application.setIdentityVerification(verification);
        application.setOrganizationCode(request.getOrganizationCode());
        application.setOrganizationOther(
                ORGANIZATION_OTHER.equalsIgnoreCase(request.getOrganizationCode())
                        ? request.getOrganizationOther() : null);
        application.setStatus(InstructorApplicationStatus.SUBMITTED);
        application.setSubmittedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        application.setReviewedAt(null);
        application.setReviewer(null);
        application.setRejectionReason(null);

        application.clearCertificates();
        List<String> urls = request.getCertificateImageUrls();
        IntStream.range(0, urls.size()).forEach(i ->
                application.addCertificate(ApplicationCertificate.builder()
                        .fileURL(urls.get(i))
                        .sortOrder(i)
                        .build()));
    }

    private List<String> certificateUrls(InstructorApplication application) {
        return application.getCertificates().stream()
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .map(ApplicationCertificate::getFileURL)
                .collect(Collectors.toList());
    }

    private MyInstructorApplicationResponse toMyResponse(InstructorApplication application) {
        return MyInstructorApplicationResponse.builder()
                .status(application.getStatus().name())
                .organizationCode(application.getOrganizationCode())
                .organizationOther(application.getOrganizationOther())
                .certificateImageUrls(certificateUrls(application))
                .identityVerified(application.getIdentityVerification() != null)
                .rejectionReason(application.getRejectionReason())
                .submittedAt(application.getSubmittedAt())
                .reviewedAt(application.getReviewedAt())
                .build();
    }

    private InstructorApplicationSummary toSummary(InstructorApplication application) {
        Account applicant = application.getAccount();
        return InstructorApplicationSummary.builder()
                .applicationId(application.getId())
                .accountId(applicant.getId())
                .nickName(applicant.getNickName())
                .email(applicant.getEmail())
                .organizationCode(application.getOrganizationCode())
                .organizationOther(application.getOrganizationOther())
                .status(application.getStatus())
                .submittedAt(application.getSubmittedAt())
                .build();
    }

    private InstructorApplicationDetail toDetail(InstructorApplication application) {
        Account applicant = application.getAccount();
        IdentityVerification verification = application.getIdentityVerification();
        return InstructorApplicationDetail.builder()
                .applicationId(application.getId())
                .accountId(applicant.getId())
                .email(applicant.getEmail())
                .nickName(applicant.getNickName())
                .status(application.getStatus())
                .organizationCode(application.getOrganizationCode())
                .organizationOther(application.getOrganizationOther())
                .certificateImageUrls(certificateUrls(application))
                .realName(verification != null ? verification.getRealName() : null)
                .birth(verification != null ? verification.getBirth() : null)
                .phoneNumber(verification != null ? verification.getPhoneNumber() : null)
                .rejectionReason(application.getRejectionReason())
                .createdAt(application.getCreatedAt())
                .submittedAt(application.getSubmittedAt())
                .reviewedAt(application.getReviewedAt())
                .reviewerNickName(application.getReviewer() != null ? application.getReviewer().getNickName() : null)
                .build();
    }
}
