package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.dto.CertificatePhotoResult;
import com.diving.pungdong.certificate.dto.StudentCertificateCreateRequest;
import com.diving.pungdong.certificate.dto.StudentCertificateResponse;
import com.diving.pungdong.certificate.dto.StudentCertificateUpdateRequest;
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
import com.diving.pungdong.identityverification.IdentityVerification;
import com.diving.pungdong.identityverification.IdentityVerificationJpaRepo;
import com.diving.pungdong.identityverification.IdentityVerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * 학생 보유 자격증 — 조회/등록/수정/삭제 + 사진 업로드.
 *
 * <p>등록·수정 모두 <b>클라이언트가 준 값을 그대로 믿지 않는다</b>: {@code source}·{@code holderName}·
 * 강사·강의는 전부 서버가 파생하고, {@code enrollmentId}·{@code photoFileKey} 는 소유를 검증한다.
 * 그 파생·검증은 {@link #applyCourseLink} 한 곳에 모여 있어 등록과 수정이 갈리지 않는다.
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

    /**
     * ⚠️ {@code NOT_SUPPORTED} — 이 메서드는 DB 를 전혀 안 건드리는데, 클래스 레벨
     * {@code @Transactional(readOnly=true)} 때문에 그냥 두면 최대 8MB S3 PUT 이 끝날 때까지
     * Hikari 커넥션이 묶인다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

        StudentCertificate cert = StudentCertificate.builder()
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
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        // source 는 요청 필드가 아니라 강의 연결 여부의 파생값이다 — 등록·수정이 같은 경로를 탄다.
        applyCourseLink(cert, account, request.getEnrollmentId(), request.getDisciplineCode());

        StudentCertificate saved = certificateRepo.save(cert);
        return toResponse(saved, resolveHolderName(account));
    }

    /* ─── 수정 ──────────────────────────────────────────────── */

    /**
     * 전면 교체(full replace) — 스칼라 필드는 보낸 값이 곧 결과다({@code issuer} 를 빼면 비워진다).
     * 없거나 남의 것이면 404(존재 숨김) — {@code getOne}/{@code delete} 와 같다.
     *
     * <p><b>사진만 "생략 = 유지"</b> 로 예외다. 사진은 별도 업로드 왕복(2-phase)을 거치는 값이라 전면
     * 교체를 그대로 적용하면 <b>번호 오타 하나 고치려고 카드를 다시 찍어 올려야 한다.</b> 그래서 비어
     * 있으면 그대로 두고, 새 key 가 왔고 지금 것과 다를 때만 교체한다(같으면 no-op). 교체 시
     * <b>옛 객체는 커밋 이후 파기</b> — PII 를 고아로 남기지 않는다. (사진 <i>제거</i>는 이 계약으로
     * 표현할 수 없다. FE 편집 폼에도 제거 버튼이 없다 — 생기면 별도 필드가 필요하다.)
     * 단 <b>기존 사진도 없으면 400</b> 이다({@link #requirePhotoAfterUpdate}) — 사진은 필수라서,
     * 필드가 아니라 <b>결과 상태</b>를 검사한다.
     *
     * <p>반대로 {@code enrollmentId} 는 전면 교체를 그대로 따른다 — 생략 = <b>연결 해제</b>. 잘못
     * 연결한 강의를 되돌릴 길이 필요하고, 사진과 달리 재입력 비용이 없다(피커에서 다시 고르면 된다).
     */
    @Transactional
    public StudentCertificateResponse update(Account account, Long id, StudentCertificateUpdateRequest request) {
        StudentCertificate cert = requireMine(account, id);
        // 등록과 같은 순서로 막는다 — 종목 → 사진 소유 → (아래) 강의 연결.
        disciplineService.getActiveByCode(request.getDisciplineCode());
        requireOwnedPhotoKey(account, request.getPhotoFileKey());

        cert.updateDetails(
                request.getDisciplineCode(),
                request.getOrganizationCode(),
                request.getOrganizationName(),
                request.getOrganizationFullName(),
                request.getLevel(),
                request.getCertificationDisplayName(),
                request.getCertificateNumber().trim(),
                request.getAcquiredAt(),
                request.getIssuer());

        requirePhotoAfterUpdate(cert, request.getPhotoFileKey());
        replacePhotoIfChanged(cert, request.getPhotoFileKey());
        applyCourseLink(cert, account, request.getEnrollmentId(), request.getDisciplineCode());

        // 더티 체킹으로 커밋 시 UPDATE. 위에서 던지면 롤백되고 사진 파기도 안 돈다(afterCommit).
        return toResponse(cert, resolveHolderName(account));
    }

    /**
     * 수정 결과가 <b>사진 없는 자격증</b>이면 거절한다 — 사진은 필수다(등록은 DTO 의 {@code @NotBlank}
     * 가 막는다).
     *
     * <p>수정 쪽만 서비스에서 검사하는 이유: 이 필드는 <b>"비움 = 기존 유지"</b> 라 빈 값이 정상 입력이다.
     * {@code @NotBlank} 를 걸면 유지 의미론이 죽어 <b>매 수정마다 사진 재업로드를 강요</b>하게 된다.
     * 필드가 아니라 <b>결과 상태</b>를 보는 검사여야 한다.
     *
     * <p>실제로 걸리는 건 <b>사진 없이 등록된 옛 행</b>뿐이다(필수가 되기 전 데이터). 그 행은 읽기·삭제는
     * 그대로 되고, 수정할 때만 사진을 붙이라고 요구한다 — DB 제약을 걸지 않은 이유이기도 하다.
     */
    private void requirePhotoAfterUpdate(StudentCertificate cert, String newPhotoKey) {
        if (!StringUtils.hasText(newPhotoKey) && !StringUtils.hasText(cert.getPhotoFileKey())) {
            throw new BadRequestException("자격증 사진을 추가해주세요.");
        }
    }

    /** 새 key 가 있고 지금 것과 다를 때만 교체 + 옛 객체 파기. 빈 값이면 유지, 같으면 no-op. */
    private void replacePhotoIfChanged(StudentCertificate cert, String newPhotoKey) {
        String currentKey = cert.getPhotoFileKey();
        if (!StringUtils.hasText(newPhotoKey) || newPhotoKey.equals(currentKey)) {
            return;
        }
        cert.replacePhoto(newPhotoKey);
        if (StringUtils.hasText(currentKey)) {
            deletePhotoAfterCommit(cert.getId(), currentKey);
        }
    }

    /**
     * 강의 연결 — <b>등록과 수정이 공유하는 단일 경로</b>다. {@code source} 와 강의 스냅샷은 여기서만
     * 정해진다. 검증이 두 벌이면 등록은 통과시키고 수정은 거절하는(혹은 그 반대) 어긋남이 생긴다.
     *
     * <p>{@code enrollmentId} 가 없으면 <b>연결 없음</b>({@code EXTERNAL} + 스냅샷 비움), 있으면
     * 소유·완료·종목 정합 3중 검증 후 강사·강의를 <b>그 시점 스냅샷</b>으로 박제한다.
     *
     * <p>단체({@code organizationCode}) 정합은 검사하지 않는다 — 코스의 단체는 "목표 단체"라 실제 발급
     * 단체가 다를 여지가 있다(제휴 발급). 종목처럼 구조적 모순이 아니다.
     */
    private void applyCourseLink(StudentCertificate cert, Account account, Long enrollmentId, String disciplineCode) {
        if (enrollmentId == null) {
            cert.unlinkCourse();
            return;
        }

        Enrollment enrollment = enrollmentRepo.findById(enrollmentId)
                .filter(e -> e.getStudent() != null && e.getStudent().getId().equals(account.getId()))
                .orElseThrow(ResourceNotFoundException::new); // 없음/비소유 통일 — 존재 숨김

        // hub 가 certifiable 로 노출하는 것과 **같은 판정**이다. 갈리면 FE 피커에 뜬 강의를 여기서 거절한다.
        if (!EnrollmentCompletion.isCertifiable(enrollment)) {
            throw new BadRequestException("아직 수강이 끝나지 않은 강의예요.");
        }

        Course course = enrollment.getCourse();
        if (course == null || !course.getDisciplineCode().equals(disciplineCode)) {
            throw new BadRequestException("강의의 종목과 자격증의 종목이 달라요.");
        }

        cert.linkCourse(
                enrollment.getId(),
                course.getId(),
                course.getTitle(),
                lastRegularRoundDate(enrollment),
                course.getInstructor() == null ? null : course.getInstructor().getNickName());
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
            deletePhotoAfterCommit(id, photoKey);
        }
    }

    /**
     * 사진 삭제를 <b>커밋 이후</b>로 미룬다.
     *
     * <p>{@code certificateRepo.delete()} 는 삭제를 <i>큐에 넣을</i> 뿐 실제 DELETE 는 커밋 시점에 나간다.
     * 그래서 그 자리에서 S3 를 지우면 (a) 네트워크 왕복 동안 DB 트랜잭션·커넥션을 붙잡고, (b) 커밋이
     * 실패하면 <b>행은 살아 있는데 사진만 사라져</b> {@code photoViewUrl} 이 404 나는 presigned 를
     * 내주게 된다. 커밋 이후로 미루면 둘 다 사라진다.
     *
     * <p>실패는 삼킨다 — 이미 커밋된 삭제를 되돌릴 수 없고, 남는 건 고아 객체 1개다.
     */
    private void deletePhotoAfterCommit(Long id, String photoKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doDeletePhoto(id, photoKey); // 트랜잭션 밖 호출(테스트 등) — 즉시 삭제
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doDeletePhoto(id, photoKey);
            }
        });
    }

    private void doDeletePhoto(Long id, String photoKey) {
        try {
            photoStorage.delete(photoKey);
        } catch (RuntimeException e) {
            log.warn("[certificate] {} 사진 삭제 실패(행은 삭제됨, 고아 객체 잔존) key={}", id, photoKey, e);
        }
    }

    /* ─── 내부 ──────────────────────────────────────────────── */

    private StudentCertificate requireMine(Account account, Long id) {
        return certificateRepo.findByIdAndOwnerId(id, account.getId())
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * 요청이 <b>남의 사진</b>을 참조하지 못하게. 빈 값은 통과 — 등록에선 DTO 의 {@code @NotBlank} 가
     * 이미 막았고, 수정에선 빈 값이 "기존 유지"라는 정상 입력이다(결과 검사는 별도).
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
                .map(IdentityVerification::getRealName)
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
