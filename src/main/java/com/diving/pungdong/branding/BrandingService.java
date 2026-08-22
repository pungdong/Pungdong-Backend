package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.block.BlockService;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.certificate.StudentCertificateService;
import com.diving.pungdong.branding.dto.BrandingProducts;
import com.diving.pungdong.branding.dto.BrandingProfileResponse;
import com.diving.pungdong.branding.dto.BrandingStats;
import com.diving.pungdong.branding.dto.BrandingUpdateRequest;
import com.diving.pungdong.branding.dto.MyBrandingResponse;
import com.diving.pungdong.branding.dto.RecordDto;
import com.diving.pungdong.branding.dto.RecordsUpdateRequest;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.enrollment.EnrollmentJpaRepo;
import com.diving.pungdong.enrollment.EnrollmentStatus;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 브랜딩 페이지(강사) / 내 프로필(일반) — 조회 합성 + 오너 편집.
 *
 * <p><b>왜 별도 패키지인가</b>: 강사 자격(certs)은 {@code instructorapplication} 소유고 계정 기본정보는
 * {@code account} 소유인데, 루트 규칙상 {@code account} 는 feature 도메인을 import 하지 않는다. 합성을
 * 별도 패키지로 빼서 {@code branding → {account, instructorapplication}} 단방향을 지킨다
 * ({@code profile} 패키지가 만든 선례).
 *
 * <p><b>생성 규칙</b>: 조회는 절대 생성하지 않는다. 첫 쓰기({@code PATCH /branding/me} 등)가 생성한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandingService {

    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final StudentCertificateService studentCertificateService;
    private final BrandingPostJpaRepo postRepo;
    private final EnrollmentJpaRepo enrollmentRepo;
    private final CourseJpaRepo courseRepo;
    private final SiteSettingsProvider siteSettings;
    /** 공개 프로필의 차단 상태 판정. */
    private final BlockService blockService;
    /** 닉네임 → 주인 + (있다면) 프로필 행. 공개 그리드와 같은 규칙을 공유한다. */
    private final PublicProfileResolver publicProfileResolver;

    /* ─── 공개 조회 ───────────────────────────────────────── */

    /**
     * 공개 프로필. <b>모든 계정에 있다</b> — 아직 아무것도 적지 않았으면 빈 프로필이 200 으로 나간다
     * (tagline·bio·활동지역·기록만 비고 닉네임·아바타·인증마크·자격은 채워진다). 근거와 "그래도 400 인
     * 셋"(없는 닉네임·탈퇴 / 유저가 내린 비공개 / 상대가 나를 차단)은 {@link PublicProfileResolver}.
     *
     * <p>400 을 쓰는 이유는 이 레포가 404 를 쓰지 않기 때문이다
     * ({@code ResourceNotFoundException} → 400, {@code GET /courses/{id}/detail} 선례).
     */
    public BrandingProfileResponse publicProfile(String nickName, Account viewer) {
        PublicProfileResolver.PublicProfile resolved = publicProfileResolver.resolve(nickName);
        Account owner = resolved.getOwner();
        AccountBranding branding = resolved.getBranding();

        // 차단의 두 방향을 다르게 답한다.
        //  · 상대가 나를 차단 → 400(존재 숨김). 차단당한 사실을 알려주지 않는다.
        //  · 내가 차단      → 200 + blockedByMe. 여기가 유일한 해제 동선이라 막으면 되돌릴 수 없다.
        boolean blockedByMe = false;
        if (viewer != null && !viewer.getId().equals(owner.getId())) {
            if (blockService.hasBlocked(owner.getId(), viewer.getId())) {
                throw new ResourceNotFoundException();
            }
            blockedByMe = blockService.hasBlocked(viewer.getId(), owner.getId());
        }
        List<InstructorApplication> approved = approvedApplicationsOf(owner.getId());
        boolean isInstructor = !approved.isEmpty();

        return BrandingProfileResponse.builder()
                .nickName(owner.getNickName())
                .avatarUrl(avatarUrlOf(owner))
                // 아직 아무것도 안 적은 계정은 여기 넷만 빈다.
                .tagline(branding == null ? null : branding.getTagline())
                .bio(branding == null ? null : branding.getBio())
                .locationLabel(branding == null ? null : branding.getLocationLabel())
                .instructor(isInstructor)
                // 강사가 아니면 null → 키 자체가 빠진다(D2).
                .disciplineCodes(isInstructor ? disciplineCodesOf(approved) : null)
                // 누구나 — 사람 표면 규칙(자기신고 수강생 레벨 + VERIFIED 강사 레벨). 빈 배열 가능(#330).
                .certs(certBadgesOf(owner.getId()))
                .records(recordDtosOf(branding))
                .stats(statsOf(branding, owner, isInstructor))
                .products(isInstructor ? productsOf(owner) : null)
                .blockedByMe(blockedByMe)
                .build();
    }

    /* ─── 오너 ───────────────────────────────────────────── */

    /**
     * 편집용 원본. <b>생성하지 않는다</b> — 미작성이면 {@code exists=false} 로 알려줄 뿐이다.
     *
     * <p>미작성이어도 <b>계정에서 파생되는 값은 채워 보낸다</b>(닉네임·아바타·인증마크·자격·검수 상태).
     * 비는 건 프로필 행이 소유하는 것뿐이다 — 공개 응답과 같은 규칙이다.
     */
    public MyBrandingResponse myBranding(Account currentUser) {
        Account owner = loadAccount(currentUser);
        return toMyBranding(brandingRepo.findByAccountId(owner.getId()).orElse(null), owner);
    }

    /**
     * 프로필 부분 수정 — <b>미생성이면 이 호출이 생성한다(upsert)</b>. 보낸 키만 반영하고, 명시적
     * {@code null} 은 "비우기"로 처리한다(키 생략 = 변경 없음).
     */
    @Transactional
    public MyBrandingResponse updateMyBranding(Account currentUser, BrandingUpdateRequest request) {
        Account owner = loadAccount(currentUser);
        AccountBranding branding = getOrCreate(owner);

        if (request.isTaglinePresent()) {
            branding.setTagline(request.getTagline());
        }
        if (request.isBioPresent()) {
            branding.setBio(request.getBio());
        }
        if (request.isLocationLabelPresent()) {
            branding.setLocationLabel(request.getLocationLabel());
        }
        return toMyBranding(branding, owner);
    }

    /**
     * 공식 기록 스냅샷 교체 — 보낸 배열이 곧 최종 상태다(빈 배열이면 전부 삭제). 미생성이면 upsert.
     *
     * <p>순서는 <b>요청 배열의 인덱스</b>가 곧 {@code sortOrder} 다. 클라이언트가 sortOrder 를 직접
     * 보내지 않게 한 이유: 중복·구멍 난 값이 들어오면 표시 순서가 비결정적이 된다. 배열 순서라는 자연스러운
     * 표현 하나만 받는다.
     */
    @Transactional
    public MyBrandingResponse replaceRecords(Account currentUser, RecordsUpdateRequest request) {
        Account owner = loadAccount(currentUser);
        AccountBranding branding = getOrCreate(owner);

        List<BrandingRecord> next = new ArrayList<>();
        List<RecordsUpdateRequest.RecordItem> items = request.getRecords();
        for (int i = 0; i < items.size(); i++) {
            RecordsUpdateRequest.RecordItem item = items.get(i);
            next.add(BrandingRecord.builder()
                    .medal(item.getMedal())
                    .eventCode(item.getEventCode())
                    .value(item.getValue())
                    .sortOrder(i)
                    .build());
        }
        branding.replaceRecords(next);

        return toMyBranding(branding, owner);
    }

    /** 발행 토글 — 승인 게이트 없음(D2). 미생성이면 upsert. */
    @Transactional
    public MyBrandingResponse updatePublished(Account currentUser, boolean published) {
        Account owner = loadAccount(currentUser);
        AccountBranding branding = getOrCreate(owner);
        branding.setPublished(published);
        return toMyBranding(branding, owner);
    }

    /**
     * 첫 쓰기 시 생성. 생성 시 {@code isPublished = true} — 발행 토글 UI 가 디자인에 없고, 이 시점엔 이미
     * 내용이 하나는 들어가므로 빈 페이지가 공개될 일이 없다.
     */
    private AccountBranding getOrCreate(Account owner) {
        return brandingRepo.findByAccountId(owner.getId())
                .orElseGet(() -> brandingRepo.save(AccountBranding.builder()
                        .account(owner)
                        .isPublished(true)
                        .build()));
    }

    /* ─── 합성 헬퍼 ───────────────────────────────────────── */

    /**
     * 오너 응답은 <b>공개 응답의 필드를 전부 포함</b>하고 거기에 검수 상태만 더한다. 디자인상 오너 뷰가
     * 퍼블릭 뷰와 같은 명함이고 편집 연필만 붙은 형태라 필요한 데이터 집합이 애초에 같기 때문이다.
     *
     * <p>이렇게 두면 FE 가 오너 화면을 <b>호출 한 번</b>으로 그린다. 안 그러면 닉네임·아바타·자격은
     * {@code GET /account/profile} 을 따로 부르고, <b>수강생 수는 아예 못 그린다</b> — 그 값이 공개
     * 응답에만 있는데 공개 엔드포인트는 유저가 내린 비공개 상태에서 400 이고, 오너 뷰는 바로 그 상태에서
     * 편집하려 들어오는 화면이라서다. 쓰기 응답도 같은 형태라 FE 가 캐시 무효화에 쓸 닉네임을 바로 얻는다.
     *
     * <p><b>{@code branding} 이 null 이어도 같은 규칙이다</b>(2026-08-22). 아직 아무것도 안 적은 계정도
     * 닉네임·아바타·인증마크·자격·검수 상태는 <b>계정과 강사 신청에서 파생</b>되므로 그대로 채운다 —
     * 비는 건 프로필 행이 소유하는 tagline·bio·활동지역·기록뿐이다. 값의 소유자가 그 값의 거동을 정한다는
     * 같은 원칙이고, 공개 응답({@code publicProfile})이 이미 그렇게 답한다.
     *
     * <p><b>왜 필요한가</b>: 그 계정도 이제 공개 프로필이 열리므로, 오너에게 <b>"내 페이지가 남에게
     * 이렇게 보인다"</b> 를 첫 작성 전에 보여줄 수 있어야 한다. 그러려면 링크를 만들 닉네임이 필요한데
     * 예전엔 {@code {"exists": false}} 만 내려가서 FE 가 미리보기 버튼을 감출 수밖에 없었다.
     *
     * <p>⚠️ <b>{@code isPublished} 만은 미작성일 때 키를 생략한다.</b> 원시가 아니라 래퍼 {@code Boolean}
     * 인 이유다 — 만들지도 않은 프로필이 {@code isPublished:false} 로 내려가면 "비공개로 존재한다" 처럼
     * 읽힌다. 그건 파생값이 아니라 프로필 행의 상태라 파생할 것도 없다.
     */
    private MyBrandingResponse toMyBranding(@Nullable AccountBranding branding, Account owner) {
        List<InstructorApplication> approved = approvedApplicationsOf(owner.getId());
        boolean isInstructor = !approved.isEmpty();
        // 검수 배너는 '신청 이력이 있는' 오너에게만. 이력이 없으면 두 키를 모두 생략한다.
        Optional<InstructorApplication> latest = latestApplicationOf(owner.getId());

        return MyBrandingResponse.builder()
                .exists(branding != null)
                // 미작성이면 키 생략 — "비공개로 존재한다" 로 읽히면 안 된다(위 javadoc).
                .isPublished(branding == null ? null : branding.isPublished())
                .nickName(owner.getNickName())
                .avatarUrl(avatarUrlOf(owner))
                // 여기 넷만 프로필 행 소유 — 미작성이면 이것만 빈다.
                .tagline(branding == null ? null : branding.getTagline())
                .bio(branding == null ? null : branding.getBio())
                .locationLabel(branding == null ? null : branding.getLocationLabel())
                .isInstructor(isInstructor)
                .disciplineCodes(isInstructor ? disciplineCodesOf(approved) : null)
                // 누구나 — 사람 표면 규칙(자기신고 수강생 레벨 + VERIFIED 강사 레벨). 빈 배열 가능(#330).
                .certs(certBadgesOf(owner.getId()))
                .records(recordDtosOf(branding))
                .stats(statsOf(branding, owner, isInstructor))
                .products(isInstructor ? productsOf(owner) : null)
                .reviewStatus(latest.map(InstructorApplication::getStatus).orElse(null))
                .approvedAt(approvedAtOf(approved))
                .build();
    }

    /**
     * 게시물 수는 공개분만 센다(숨긴 글은 남에게도 나에게도 "올린 글" 로 안 보이는 게 일관적이다).
     *
     * <p>{@code branding} 이 null 이면 아직 아무것도 적지 않은 계정 — 글이 있을 수 없으니 0 이다
     * (수강생 수는 프로필 행과 무관하게 나온다).
     */
    private BrandingStats statsOf(@Nullable AccountBranding branding, Account owner, boolean isInstructor) {
        return BrandingStats.builder()
                .posts(branding == null ? 0
                        : (int) postRepo.countByBranding_IdAndIsHiddenFalseAndShowOnProfileTrue(branding.getId()))
                .students(isInstructor ? studentCountOf(owner.getId()) : null)
                .build();
    }

    private Integer studentCountOf(Long instructorId) {
        return (int) enrollmentRepo.countDistinctStudentsOfInstructor(instructorId, EnrollmentStatus.CONFIRMED);
    }

    private BrandingProducts productsOf(Account owner) {
        // 런칭 후 둘러보기가 데모 시드를 가리면 이 숫자도 같이 가려야 "강의 8개"인데 목록엔 3개인 상황이 안 생긴다.
        long lessons = siteSettings.current().showSeededCourses()
                ? courseRepo.countByInstructorIdAndStatus(owner.getId(), CourseStatus.OPEN)
                : courseRepo.countByInstructorIdAndStatusAndSeededFalse(owner.getId(), CourseStatus.OPEN);
        return BrandingProducts.builder().lessons((int) lessons).build();
    }

    private Account loadAccount(Account currentUser) {
        // @CurrentUser 는 detach 상태일 수 있어 LAZY(profilePhoto) 접근 위해 트랜잭션 안에서 재로드.
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }

    private String avatarUrlOf(Account account) {
        return ProfilePhoto.displayUrlOf(account);
    }

    private List<InstructorApplication> approvedApplicationsOf(Long accountId) {
        return applicationRepo.findByAccountIdAndStatus(accountId, InstructorApplicationStatus.APPROVED);
    }

    /** 검수 배너용 — 종목별로 여러 건일 수 있어 가장 최근 신청의 상태를 쓴다. */
    private Optional<InstructorApplication> latestApplicationOf(Long accountId) {
        return applicationRepo.findByAccountIdOrderByIdDesc(accountId).stream().findFirst();
    }

    /**
     * 승인 시각 — 여러 종목을 승인받았으면 <b>가장 이른</b> 것(= 이 계정이 강사가 된 시점). 재제출은
     * {@code reviewedAt} 을 null 로 되돌리지만 그 신청은 동시에 SUBMITTED 가 되어 여기 필터에서 빠진다.
     */
    private OffsetDateTime approvedAtOf(List<InstructorApplication> approved) {
        return approved.stream()
                .map(InstructorApplication::getReviewedAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private List<String> disciplineCodesOf(List<InstructorApplication> approved) {
        return approved.stream()
                .map(InstructorApplication::getDisciplineCode)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 자격 뱃지 — 사람 표면 규칙({@code certificate.CertificateBadgePolicy}): 수강생 레벨은 자기신고 그대로,
     * 강사 레벨은 VERIFIED 만, (종목,단체)별 최고 1장, 레벨 내림차순. 강사 자격 표면({@code verifiedBadgesOf})이 아니다.
     */
    private List<BrandingProfileResponse.CertBadge> certBadgesOf(Long accountId) {
        return studentCertificateService.displayBadgesOf(accountId).stream()
                .map(b -> BrandingProfileResponse.CertBadge.builder()
                        .disciplineCode(b.getDisciplineCode())
                        .organizationCode(b.getOrganizationCode())
                        .organizationOther(b.getOrganizationOther())
                        .level(b.getLevel())
                        .verified(b.isVerified())
                        .build())
                .collect(Collectors.toList());
    }

    /** {@code branding} 이 null 이면 빈 배열 — 키를 빼면 FE 가 배열로 다루다 터진다. */
    private List<RecordDto> recordDtosOf(@Nullable AccountBranding branding) {
        if (branding == null) {
            return List.of();
        }
        return branding.getRecords().stream()
                .map(record -> RecordDto.builder()
                        .medal(record.getMedal())
                        .eventCode(record.getEventCode())
                        .value(record.getValue())
                        .build())
                .collect(Collectors.toList());
    }
}
