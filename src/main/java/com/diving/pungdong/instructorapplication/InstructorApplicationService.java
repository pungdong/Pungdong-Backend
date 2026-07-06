package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
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
    private final DisciplineService disciplineService;
    private final CertificateImageStorage certificateImageStorage;

    /* ─── 자격증 이미지 업로드 (2-phase 1단계) ───────────────── */

    @Transactional
    public CertificateImageResult uploadCertificateImage(Account account, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException();
        }
        try {
            String key = certificateImageStorage.store(image, account.getId());
            return CertificateImageResult.builder().fileKey(key).build();
        } catch (IOException e) {
            throw new BadRequestException();
        }
    }

    /* ─── 신청 제출 / 재제출 ─────────────────────────────────── */

    @Transactional
    public InstructorApplicationResult submit(Account account, InstructorApplicationSubmitRequest request) {
        Account managed = loadAccount(account);
        Discipline discipline = disciplineService.getActiveByCode(request.getDisciplineCode());
        IdentityVerification verification = resolveVerification(managed, request.getVerificationId());
        validateCertification(discipline, request);

        InstructorApplication application = applicationRepo
                .findByAccountIdAndDisciplineCode(managed.getId(), discipline.getCode())
                .orElse(null);

        if (application == null) {
            application = InstructorApplication.builder()
                    .account(managed)
                    .disciplineCode(discipline.getCode())
                    .createdAt(LocalDateTime.now())
                    .build();
        } else {
            switch (application.getStatus()) {
                case SUBMITTED:
                    throw new BadRequestException(); // 이 종목 이미 심사 중 — 중복 신청 불가
                case APPROVED:
                    throw new BadRequestException(); // 이 종목 이미 강사 — 재신청 불필요
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
     * 내 신청 수정·재제출 (PUT /me). 종목별로 — 해당 종목의 반려/심사전 건을 고쳐 SUBMITTED 로.
     * 승인된 건은 수정 불가.
     */
    @Transactional
    public InstructorApplicationResult resubmit(Account account, InstructorApplicationSubmitRequest request) {
        Account managed = loadAccount(account);
        Discipline discipline = disciplineService.getActiveByCode(request.getDisciplineCode());
        InstructorApplication application = applicationRepo
                .findByAccountIdAndDisciplineCode(managed.getId(), discipline.getCode())
                .orElseThrow(BadRequestException::new); // 그 종목에 수정할 신청이 없음

        if (application.getStatus() == InstructorApplicationStatus.APPROVED) {
            throw new BadRequestException(); // 승인된 신청은 수정 불가
        }

        IdentityVerification verification = resolveVerification(managed, request.getVerificationId());
        validateCertification(discipline, request);

        applyFields(application, request, verification);
        InstructorApplication saved = applicationRepo.save(application);
        return InstructorApplicationResult.builder()
                .applicationId(saved.getId())
                .status(saved.getStatus())
                .build();
    }

    /**
     * 자격증 관리 — 이미 승인된(APPROVED) 강사가 그 종목에 자격증 1건을 추가한다. MVP 는 검수 없이
     * 바로 append (상태 유지). 검수 중/반려 상태는 신청/재제출(submit/resubmit) 경로로 다룬다.
     */
    @Transactional
    public InstructorApplicationResult addCertificate(Account account, AddCertificateRequest request) {
        Account managed = loadAccount(account);
        Discipline discipline = disciplineService.getActiveByCode(request.getDisciplineCode());
        InstructorApplication application = applicationRepo
                .findByAccountIdAndDisciplineCode(managed.getId(), discipline.getCode())
                .orElseThrow(BadRequestException::new); // 그 종목 신청이 없음

        if (application.getStatus() != InstructorApplicationStatus.APPROVED) {
            throw new BadRequestException(); // 승인된 강사만 추가 가능 (검수중/반려는 submit/resubmit)
        }
        if (isBlank(request.getOrganizationCode()) || isBlank(request.getFileKey())) {
            throw new BadRequestException();
        }
        if (ORGANIZATION_OTHER.equalsIgnoreCase(request.getOrganizationCode()) && isBlank(request.getOrganizationOther())) {
            throw new BadRequestException();
        }

        application.addCertificate(ApplicationCertificate.builder()
                .organizationCode(request.getOrganizationCode())
                .organizationOther(ORGANIZATION_OTHER.equalsIgnoreCase(request.getOrganizationCode())
                        ? request.getOrganizationOther() : null)
                .fileKey(request.getFileKey())
                .sortOrder(application.getCertificates().size())
                .build());
        application.setUpdatedAt(LocalDateTime.now());
        InstructorApplication saved = applicationRepo.save(application);
        return InstructorApplicationResult.builder()
                .applicationId(saved.getId())
                .status(saved.getStatus())
                .build();
    }

    /* ─── 내 신청 조회 (프로필 탭) ───────────────────────────── */

    /** 내 신청 목록 — 종목별 여러 건. 미신청 종목은 리스트에 없음(FE 가 "신청하기" 노출). */
    public List<MyInstructorApplicationResponse> getMyApplications(Account account) {
        return applicationRepo.findByAccountIdOrderByIdDesc(account.getId()).stream()
                .map(this::toMyResponse)
                .collect(Collectors.toList());
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
        // SMS 2단계 도입으로 READY/FAILED 레코드도 소유로 존재한다 — VERIFIED 만 신청의 전제로 인정.
        if (verification.getStatus() != IdentityVerificationStatus.VERIFIED) {
            throw new BadRequestException(); // 본인확인 미완료(READY/FAILED)는 참조 불가
        }
        return verification;
    }

    /**
     * 종목의 자격증 필수 여부에 따른 조건부 검증. requiresCertification 종목은 자격증 1건 이상,
     * 각 자격증마다 단체(+OTHER 직접입력)·이미지 필수. 그 외(수영/서핑)는 생략 가능.
     */
    private void validateCertification(Discipline discipline, InstructorApplicationSubmitRequest request) {
        if (!discipline.isRequiresCertification()) {
            return; // 자격증 불필요 종목 — 자격증/단체 생략 가능
        }
        List<ApplicationCertificateDto> certs = request.getCertificates();
        if (certs == null || certs.isEmpty()) {
            throw new BadRequestException(); // 자격증 1건 이상 필수
        }
        for (ApplicationCertificateDto cert : certs) {
            if (isBlank(cert.getFileKey())) {
                throw new BadRequestException(); // 자격증 이미지 필수
            }
            if (isBlank(cert.getOrganizationCode())) {
                throw new BadRequestException(); // 발급 단체 필수
            }
            if (ORGANIZATION_OTHER.equalsIgnoreCase(cert.getOrganizationCode()) && isBlank(cert.getOrganizationOther())) {
                throw new BadRequestException(); // 기타 단체는 직접입력 필수
            }
        }
    }

    private void applyFields(InstructorApplication application, InstructorApplicationSubmitRequest request,
                             IdentityVerification verification) {
        application.setDisciplineCode(request.getDisciplineCode());
        application.setIdentityVerification(verification);
        application.setStatus(InstructorApplicationStatus.SUBMITTED);
        application.setSubmittedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        application.setReviewedAt(null);
        application.setReviewer(null);
        application.setRejectionReason(null);
        // 보험(선택) — 제출/재제출은 전체 스냅샷이라, 안 보내면 해제됨(자격증과 동일). FE 가 유지 시 prefill 로 재전송.
        application.setInsuranceFileKey(request.getInsuranceFileKey());

        application.clearCertificates();
        List<ApplicationCertificateDto> certs = request.getCertificates();
        if (certs != null) {
            IntStream.range(0, certs.size()).forEach(i -> {
                ApplicationCertificateDto c = certs.get(i);
                application.addCertificate(ApplicationCertificate.builder()
                        .organizationCode(c.getOrganizationCode())
                        .organizationOther(ORGANIZATION_OTHER.equalsIgnoreCase(c.getOrganizationCode())
                                ? c.getOrganizationOther() : null)
                        .fileKey(c.getFileKey())
                        .sortOrder(i)
                        .build());
            });
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 보험 이미지(선택) 표시용 한시 URL — key 없으면 null(presign 호출 안 함). */
    private String insuranceViewUrl(String insuranceFileKey) {
        return isBlank(insuranceFileKey) ? null : certificateImageStorage.viewUrl(insuranceFileKey);
    }

    /**
     * 조회 응답용 자격증 DTO — 저장 key 는 그대로 echo 하고, 표시용 {@code viewUrl} 은 이 시점에
     * presigned(짧은 TTL)로 발급한다. 목록(toSummary)엔 이미지가 안 나가므로 발급도 안 함.
     */
    private List<ApplicationCertificateDto> certificateDtos(InstructorApplication application) {
        return application.getCertificates().stream()
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .map(c -> ApplicationCertificateDto.builder()
                        .organizationCode(c.getOrganizationCode())
                        .organizationOther(c.getOrganizationOther())
                        .fileKey(c.getFileKey())
                        .viewUrl(certificateImageStorage.viewUrl(c.getFileKey()))
                        .build())
                .collect(Collectors.toList());
    }

    private MyInstructorApplicationResponse toMyResponse(InstructorApplication application) {
        return MyInstructorApplicationResponse.builder()
                .disciplineCode(application.getDisciplineCode())
                .status(application.getStatus().name())
                .certificates(certificateDtos(application))
                .insuranceFileKey(application.getInsuranceFileKey())
                .insuranceViewUrl(insuranceViewUrl(application.getInsuranceFileKey()))
                .identityVerified(application.getIdentityVerification() != null)
                .rejectionReason(application.getRejectionReason())
                .submittedAt(application.getSubmittedAt())
                .reviewedAt(application.getReviewedAt())
                .build();
    }

    private InstructorApplicationSummary toSummary(InstructorApplication application) {
        Account applicant = application.getAccount();
        List<String> orgCodes = application.getCertificates().stream()
                .map(ApplicationCertificate::getOrganizationCode)
                .filter(c -> c != null)
                .distinct()
                .collect(Collectors.toList());
        return InstructorApplicationSummary.builder()
                .applicationId(application.getId())
                .accountId(applicant.getId())
                .nickName(applicant.getNickName())
                .email(applicant.getEmail())
                .disciplineCode(application.getDisciplineCode())
                .organizationCodes(orgCodes)
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
                .disciplineCode(application.getDisciplineCode())
                .certificates(certificateDtos(application))
                .insuranceFileKey(application.getInsuranceFileKey())
                .insuranceViewUrl(insuranceViewUrl(application.getInsuranceFileKey()))
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
