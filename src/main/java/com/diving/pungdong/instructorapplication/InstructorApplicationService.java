package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.certificate.CertificateVerificationService;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.certificate.StudentCertificateService;
import com.diving.pungdong.discipline.Discipline;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.IdentityVerificationRequiredException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import com.diving.pungdong.instructorapplication.dto.CertificateImageResult;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationCounts;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationDetail;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationResult;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationSubmitRequest;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationSummary;
import com.diving.pungdong.instructorapplication.dto.MyInstructorApplicationResponse;
import com.diving.pungdong.instructorapplication.storage.CertificateImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 강사 신청 도메인 서비스 — 본인확인 → (내 자격증 등록) → 제출/재제출 → 어드민 승인/반려.
 *
 * <p>상태머신 전이는 모두 여기서 강제한다. 컨트롤러는 입출력 매핑만 담당.
 * 자격증의 검증 상태(PENDING/VERIFIED/REJECTED)는 {@link CertificateVerificationService} 에 위임한다 —
 * 제출·승인·반려가 그 도메인의 Rule B 를 부른다(의존 방향: instructorapplication → certificate).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorApplicationService {

    public static final String MSG_CERTIFICATE_REQUIRED = "강사 레벨 자격증을 1개 이상 등록해주세요.";

    private final InstructorApplicationJpaRepo applicationRepo;
    private final IdentityVerificationJpaRepo identityVerificationRepo;
    private final AccountJpaRepo accountRepo;
    private final DisciplineService disciplineService;
    private final CertificateImageStorage certificateImageStorage;
    private final CertificateVerificationService certificateVerificationService;
    private final StudentCertificateService studentCertificateService;
    private final StudentCertificateJpaRepo studentCertificateRepo;

    /* ─── 보험 이미지 업로드 (2-phase 1단계) ───────────────── */

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
        requireOwnedFileKey(managed, request.getInsuranceFileKey());

        InstructorApplication application = applicationRepo
                .findByAccountIdAndDisciplineCode(managed.getId(), discipline.getCode())
                .orElse(null);

        if (application == null) {
            application = InstructorApplication.builder()
                    .account(managed)
                    .disciplineCode(discipline.getCode())
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
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

        return applyAndAttach(application, managed, discipline, request, verification);
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
        requireOwnedFileKey(managed, request.getInsuranceFileKey());
        return applyAndAttach(application, managed, discipline, request, verification);
    }

    /**
     * 제출/재제출 공통 — 필드 반영 → 저장(id 확보) → 자격증 첨부(Rule B: 검증·자동첨부·PENDING·NEW 검수행).
     * 자격증 필요 종목인데 첨부가 0 이면 400(전부 롤백).
     */
    private InstructorApplicationResult applyAndAttach(InstructorApplication application, Account owner,
                                                       Discipline discipline,
                                                       InstructorApplicationSubmitRequest request,
                                                       IdentityVerification verification) {
        List<Long> previouslyAttached = new ArrayList<>(application.getCertificateIds());
        applyFields(application, request, verification);
        InstructorApplication saved = applicationRepo.save(application);

        List<Long> attached = certificateVerificationService.attachToApplication(
                saved.getId(), owner.getId(), discipline.getCode(), request.getCertificateIds(), previouslyAttached);
        if (discipline.isRequiresCertification() && attached.isEmpty()) {
            throw new BadRequestException(MSG_CERTIFICATE_REQUIRED);
        }
        saved.replaceCertificateIds(attached);

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
        // 단체 칩은 첨부 자격증에서 — 페이지 전체를 한 번에 읽어 행마다 쿼리가 나가지 않게.
        Set<Long> allIds = page.getContent().stream()
                .flatMap(a -> a.getCertificateIds().stream())
                .collect(Collectors.toSet());
        Map<Long, StudentCertificate> certs = allIds.isEmpty() ? Map.of()
                : studentCertificateRepo.findAllById(allIds).stream()
                        .collect(Collectors.toMap(StudentCertificate::getId, Function.identity()));
        return page.map(a -> toSummary(a, certs));
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
        application.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
        application.setRejectionReason(null);
        applicationRepo.save(application);

        // 강사 권한 부여 — STUDENT 유지 + INSTRUCTOR 추가 (additive). 권한은 매 요청 DB 에서
        // 재계산되므로 토큰 재발급 없이 다음 요청부터 즉시 반영된다.
        Account applicant = application.getAccount();
        applicant.getRoles().add(Role.INSTRUCTOR);
        applicant.setIsCertified(true);
        accountRepo.save(applicant);

        // Rule B — 첨부 자격증 VERIFIED(= 인증마크) + 심사 중 새로 올라온 강사레벨은 ADDITIONAL 큐로.
        certificateVerificationService.onApplicationApproved(application.getId(), applicant.getId(),
                application.getDisciplineCode(), new ArrayList<>(application.getCertificateIds()), reviewer.getId());
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
        application.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
        application.setRejectionReason(reason);
        applicationRepo.save(application);

        certificateVerificationService.onApplicationRejected(application.getId(),
                new ArrayList<>(application.getCertificateIds()), reason, reviewer.getId());
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
        // 미완료는 "본인인증 선행 필요" 분기라 403(-1017) — 수강신청 게이트와 동일 신호(위 없는/남의 id 는 400 유지).
        if (verification.getStatus() != IdentityVerificationStatus.VERIFIED) {
            throw new IdentityVerificationRequiredException();
        }
        return verification;
    }

    /**
     * 보험 이미지의 저장 참조가 <b>본인이 올린 것</b>인지 — 없으면 남의 이미지를 자기 신청에 붙여 열람할 수 있다
     * ({@link CertificateImageStorage#isOwnedBy} 의 유출 시나리오 참고). 빈 값은 통과(선택 필드).
     */
    private void requireOwnedFileKey(Account owner, String fileKey) {
        if (isBlank(fileKey)) {
            return;
        }
        if (!CertificateImageStorage.isOwnedBy(fileKey, owner.getId())) {
            throw new BadRequestException(); // 남의 저장 참조 — 존재 숨김(사유를 특정하지 않는다)
        }
    }

    private void applyFields(InstructorApplication application, InstructorApplicationSubmitRequest request,
                             IdentityVerification verification) {
        application.setDisciplineCode(request.getDisciplineCode());
        application.setIdentityVerification(verification);
        application.setStatus(InstructorApplicationStatus.SUBMITTED);
        application.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        application.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        application.setReviewedAt(null);
        application.setReviewer(null);
        application.setRejectionReason(null);
        // 보험(선택) — 제출/재제출은 전체 스냅샷이라, 안 보내면 해제됨. FE 가 유지 시 prefill 로 재전송.
        application.setInsuranceFileKey(request.getInsuranceFileKey());
        application.replaceCertificateIds(List.of()); // 첨부는 attachToApplication 결과로 다시 채운다
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 보험 이미지(선택) 표시용 한시 URL — key 없으면 null(presign 호출 안 함). */
    private String insuranceViewUrl(String insuranceFileKey) {
        return isBlank(insuranceFileKey) ? null : certificateImageStorage.viewUrl(insuranceFileKey);
    }

    /** 첨부 id 중 아직 존재하는 것만(사용자가 지운 자격증은 빠진다) — 순서 유지. */
    private List<Long> existingCertificateIds(InstructorApplication application) {
        List<Long> ids = application.getCertificateIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> existing = studentCertificateRepo.findAllById(ids).stream()
                .map(StudentCertificate::getId)
                .collect(Collectors.toSet());
        return ids.stream().filter(existing::contains).collect(Collectors.toList());
    }

    private MyInstructorApplicationResponse toMyResponse(InstructorApplication application) {
        return MyInstructorApplicationResponse.builder()
                .disciplineCode(application.getDisciplineCode())
                .status(application.getStatus().name())
                .certificateIds(existingCertificateIds(application))
                .insuranceFileKey(application.getInsuranceFileKey())
                .insuranceViewUrl(insuranceViewUrl(application.getInsuranceFileKey()))
                .identityVerified(application.getIdentityVerification() != null)
                .rejectionReason(application.getRejectionReason())
                .submittedAt(application.getSubmittedAt())
                .reviewedAt(application.getReviewedAt())
                .build();
    }

    private InstructorApplicationSummary toSummary(InstructorApplication application,
                                                   Map<Long, StudentCertificate> certs) {
        Account applicant = application.getAccount();
        List<String> orgCodes = application.getCertificateIds().stream()
                .map(certs::get)
                .filter(c -> c != null && c.getOrganizationCode() != null)
                .map(StudentCertificate::getOrganizationCode)
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
                .certificates(studentCertificateService.adminViewsOf(new ArrayList<>(application.getCertificateIds())))
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
