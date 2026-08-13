package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.CertificatePhotoResult;
import com.diving.pungdong.certificate.dto.StudentCertificateCreateRequest;
import com.diving.pungdong.certificate.dto.StudentCertificateResponse;
import com.diving.pungdong.certificate.storage.StudentCertificatePhotoStorage;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.RoundKind;
import com.diving.pungdong.discipline.DisciplineService;
import com.diving.pungdong.enrollment.Enrollment;
import com.diving.pungdong.enrollment.EnrollmentCompletion;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentRound;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.validation.ImageUploadPolicy;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 학생 보유 자격증 — 조회/등록/삭제 + 사진 업로드.
 *
 * <p>등록은 <b>클라이언트가 준 값을 그대로 믿지 않는다</b>: {@code source}·{@code holderName}·강사·강의는
 * 전부 서버가 파생하고, {@code enrollmentId}·{@code photoFileKey} 는 소유를 검증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentCertificateService {

    private final StudentCertificateJpaRepo certificateRepo;
    private final StudentCertificatePhotoStorage photoStorage;
    private final DisciplineService disciplineService;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final IdentityVerificationJpaRepo identityVerificationRepo;

    /* ─── 사진 업로드 (2-phase 1단계) ───────────────────────── */

    @Transactional
    public CertificatePhotoResult uploadPhoto(Account account, MultipartFile image) {
        // S3 를 건드리기 전에 막는다 — 빈 파일 / 타입 위조 / 8MB 초과.
        ImageUploadPolicy.validate(image);
        try {
            return CertificatePhotoResult.builder()
                    .fileKey(photoStorage.store(image, account.getId()))
                    .build();
        } catch (IOException e) {
            throw new BadRequestException("사진 업로드에 실패했어요. 다시 시도해주세요.");
        }
    }

    /* ─── 조회 ──────────────────────────────────────────────── */

    /** 내 자격증 목록 — 최근 취득 순. 없으면 빈 리스트(404 아님). */
    public List<StudentCertificateResponse> getMine(Account account) {
        String holderName = resolveHolderName(account);
        return certificateRepo.findByOwnerIdOrderByAcquiredAtDescIdDesc(account.getId()).stream()
                .map(c -> toResponse(c, holderName))
                .collect(Collectors.toList());
    }

    /**
     * 단건 — 상세 진입 시 presigned 를 <b>새로</b> 발급받는 용도(TTL 3분).
     * 없거나 남의 것이면 404(존재 숨김).
     */
    public StudentCertificateResponse getOne(Account account, Long id) {
        StudentCertificate cert = requireMine(account, id);
        return toResponse(cert, resolveHolderName(account));
    }

    /* ─── 등록 ──────────────────────────────────────────────── */

    @Transactional
    public StudentCertificateResponse register(Account account, StudentCertificateCreateRequest request) {
        // 종목은 실제 카탈로그에 있어야 한다(단체 코드는 Sanity 소유라 대조하지 않는다).
        disciplineService.getActiveByCode(request.getDisciplineCode());
        requireOwnedPhotoKey(account, request.getPhotoFileKey());

        StudentCertificate.StudentCertificateBuilder builder = StudentCertificate.builder()
                .owner(account)
                .disciplineCode(request.getDisciplineCode())
                .organizationCode(request.getOrganizationCode())
                .organizationName(request.getOrganizationName())
                .organizationFullName(request.getOrganizationFullName())
                .level(request.getLevel())
                .certificationDisplayName(request.getCertificationDisplayName())
                .certificateNumber(request.getCertificateNumber().trim())
                .acquiredAt(request.getAcquiredAt())
                .issuer(request.getIssuer())
                .photoFileKey(request.getPhotoFileKey())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC));

        // source 는 요청 필드가 아니라 강의 연결 여부의 파생값이다.
        if (request.getEnrollmentId() == null) {
            builder.source(CertificateSource.EXTERNAL);
        } else {
            applyCourseSnapshot(builder, account, request);
        }

        StudentCertificate saved = certificateRepo.save(builder.build());
        return toResponse(saved, resolveHolderName(account));
    }

    /**
     * 강의 연결 — 소유·완료·종목 정합을 검증하고 강사·강의를 <b>등록 시점 스냅샷</b>으로 박제한다.
     *
     * <p>단체({@code organizationCode}) 정합은 검사하지 않는다 — 코스의 단체는 "목표 단체"라 실제 발급
     * 단체가 다를 여지가 있다(제휴 발급). 종목처럼 구조적 모순이 아니다.
     */
    private void applyCourseSnapshot(StudentCertificate.StudentCertificateBuilder builder,
                                     Account account, StudentCertificateCreateRequest request) {
        Enrollment enrollment = enrollmentRepo.findById(request.getEnrollmentId())
                .filter(e -> e.getStudent() != null && e.getStudent().getId().equals(account.getId()))
                .orElseThrow(ResourceNotFoundException::new); // 없음/비소유 통일 — 존재 숨김

        // 화면(hub 의 status=COMPLETED)과 같은 판정을 공유한다. 갈리면 FE 가 띄운 강의를 여기서 거절하게 된다.
        if (!EnrollmentCompletion.isFullyCompleted(enrollment)) {
            throw new BadRequestException("아직 수강이 끝나지 않은 강의예요.");
        }

        Course course = enrollment.getCourse();
        if (course == null || !course.getDisciplineCode().equals(request.getDisciplineCode())) {
            throw new BadRequestException("강의의 종목과 자격증의 종목이 달라요.");
        }

        builder.source(CertificateSource.PUNGDONG)
                .enrollmentId(enrollment.getId())
                .courseId(course.getId())
                .courseTitle(course.getTitle())
                .courseCompletedAt(lastRegularRoundDate(enrollment))
                .instructorName(course.getInstructor() == null ? null : course.getInstructor().getNickName());
    }

    /** 수료일 = 마지막 정규 회차 날짜(civil date). 날짜 없는 회차는 제외. */
    private LocalDate lastRegularRoundDate(Enrollment enrollment) {
        return enrollment.getRounds().stream()
                .filter(r -> r.getRoundKind() == RoundKind.REGULAR && r.getDate() != null)
                .map(EnrollmentRound::getDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /* ─── 삭제 ──────────────────────────────────────────────── */

    /**
     * 하드 삭제 — DB 행 + S3 객체. 자기 신고 데이터라 법정 보존 대상이 아니다.
     *
     * <p>사진 삭제는 <b>best-effort</b>: 실패해도 롤백하지 않는다. 실물 PII 를 남기지 않는 게 우선이라
     * 재시도 가능한 고아 1개보다 "삭제가 안 됨"이 나쁘다({@code ProfilePhotoService} 와 같은 순서 원칙).
     */
    @Transactional
    public void delete(Account account, Long id) {
        StudentCertificate cert = requireMine(account, id);
        String photoKey = cert.getPhotoFileKey();
        certificateRepo.delete(cert);

        if (StringUtils.hasText(photoKey)) {
            try {
                photoStorage.delete(photoKey);
            } catch (RuntimeException e) {
                log.warn("[certificate] {} 사진 삭제 실패(행은 삭제됨) key={}", id, photoKey, e);
            }
        }
    }

    /* ─── 내부 ──────────────────────────────────────────────── */

    private StudentCertificate requireMine(Account account, Long id) {
        return certificateRepo.findByIdAndOwnerId(id, account.getId())
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * 등록 JSON 이 <b>남의 사진</b>을 참조하지 못하게. 빈 값은 통과(사진은 선택).
     * 유출된 presigned URL 에서 key 를 뽑아 붙이는 경로를 막는다.
     */
    private void requireOwnedPhotoKey(Account account, String photoFileKey) {
        if (!StringUtils.hasText(photoFileKey)) {
            return;
        }
        if (!StudentCertificatePhotoStorage.isOwnedBy(photoFileKey, account.getId())) {
            throw new BadRequestException("사진을 다시 업로드해주세요.");
        }
    }

    /**
     * 카드에 인쇄되는 보유자 이름 — <b>세션에서 파생</b>한다(레포 규칙: identity 는 입력에서 받지 않는다).
     * 본인확인 실명이 있으면 그걸, 없으면 닉네임.
     *
     * <p>⚠️ 실물 자격증은 로마자 표기(SUMIN LEE)인 경우가 흔한데 이 파생은 한글을 준다.
     * "사진이 진실"이라 지금은 허용 — 정확히 하려면 폼 입력 필드 승격이 필요하다(디자인에 칸이 없다).
     */
    private String resolveHolderName(Account account) {
        return identityVerificationRepo
                .findTopByAccountIdAndStatusOrderByIdDesc(account.getId(), IdentityVerificationStatus.VERIFIED)
                .map(v -> v.getRealName())
                .filter(StringUtils::hasText)
                .orElse(account.getNickName());
    }

    private StudentCertificateResponse toResponse(StudentCertificate cert, String holderName) {
        String viewUrl = StringUtils.hasText(cert.getPhotoFileKey())
                ? photoStorage.viewUrl(cert.getPhotoFileKey())
                : null;
        return StudentCertificateResponse.of(cert, holderName, viewUrl);
    }
}
