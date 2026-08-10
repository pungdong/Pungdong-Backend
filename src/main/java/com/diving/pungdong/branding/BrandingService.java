package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.branding.dto.BrandingProfileResponse;
import com.diving.pungdong.branding.dto.BrandingUpdateRequest;
import com.diving.pungdong.branding.dto.MyBrandingResponse;
import com.diving.pungdong.branding.dto.RecordDto;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    /* ─── 공개 조회 ───────────────────────────────────────── */

    /**
     * 공개 프로필. 발행되지 않았거나 없는 닉네임이면 <b>400(존재 숨김)</b> — 이 레포는 404 를 쓰지 않는다
     * ({@code ResourceNotFoundException} → 400, {@code GET /courses/{id}/detail} 선례).
     */
    public BrandingProfileResponse publicProfile(String nickName) {
        AccountBranding branding = brandingRepo.findPublishedByNickName(nickName).stream()
                .findFirst()
                .orElseThrow(ResourceNotFoundException::new);

        Account owner = branding.getAccount();
        List<InstructorApplication> approved = approvedApplicationsOf(owner.getId());
        boolean isInstructor = !approved.isEmpty();

        return BrandingProfileResponse.builder()
                .nickName(owner.getNickName())
                .avatarUrl(avatarUrlOf(owner))
                .tagline(branding.getTagline())
                .bio(branding.getBio())
                .locationLabel(branding.getLocationLabel())
                .isInstructor(isInstructor)
                // 강사가 아니면 null → 키 자체가 빠진다(D2).
                .disciplineCodes(isInstructor ? disciplineCodesOf(approved) : null)
                .certs(isInstructor ? certBadgesOf(approved) : null)
                .records(recordDtosOf(branding))
                .build();
    }

    /* ─── 오너 ───────────────────────────────────────────── */

    /** 편집용 원본. 미생성이면 {@code exists=false} — <b>생성하지 않는다</b>. */
    public MyBrandingResponse myBranding(Account currentUser) {
        Account owner = loadAccount(currentUser);
        return brandingRepo.findByAccountId(owner.getId())
                .map(branding -> toMyBranding(branding, owner))
                .orElseGet(MyBrandingResponse::notCreated);
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

    private MyBrandingResponse toMyBranding(AccountBranding branding, Account owner) {
        List<InstructorApplication> approved = approvedApplicationsOf(owner.getId());
        // 검수 배너는 '신청 이력이 있는' 오너에게만. 이력이 없으면 두 키를 모두 생략한다.
        Optional<InstructorApplication> latest = latestApplicationOf(owner.getId());

        return MyBrandingResponse.builder()
                .exists(true)
                .isPublished(branding.isPublished())
                .nickName(owner.getNickName())
                .avatarUrl(avatarUrlOf(owner))
                .tagline(branding.getTagline())
                .bio(branding.getBio())
                .locationLabel(branding.getLocationLabel())
                .records(recordDtosOf(branding))
                .reviewStatus(latest.map(InstructorApplication::getStatus).orElse(null))
                .approvedAt(approvedAtOf(approved))
                .build();
    }

    private Account loadAccount(Account currentUser) {
        // @CurrentUser 는 detach 상태일 수 있어 LAZY(profilePhoto) 접근 위해 트랜잭션 안에서 재로드.
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }

    private String avatarUrlOf(Account account) {
        ProfilePhoto photo = account.getProfilePhoto();
        return photo == null ? null : photo.getImageUrl();
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

    private List<BrandingProfileResponse.CertBadge> certBadgesOf(List<InstructorApplication> approved) {
        return approved.stream()
                .flatMap(application -> application.getCertificates().stream()
                        .map(cert -> BrandingProfileResponse.CertBadge.builder()
                                .disciplineCode(application.getDisciplineCode())
                                .organizationCode(cert.getOrganizationCode())
                                .organizationOther(cert.getOrganizationOther())
                                .build()))
                .collect(Collectors.toList());
    }

    private List<RecordDto> recordDtosOf(AccountBranding branding) {
        return branding.getRecords().stream()
                .map(record -> RecordDto.builder()
                        .medal(record.getMedal())
                        .eventCode(record.getEventCode())
                        .value(record.getValue())
                        .build())
                .collect(Collectors.toList());
    }
}
