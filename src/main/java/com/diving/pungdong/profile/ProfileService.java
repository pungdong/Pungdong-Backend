package com.diving.pungdong.profile;

import com.diving.pungdong.certificate.StudentCertificateService;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.profile.dto.AccountProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 마이페이지 프로필 합성 — account 기본정보·사진 + instructorapplication 승인 자격. account 패키지는
 * instructorapplication 을 모르므로(단방향) 합성을 이 별도 feature 패키지에서 한다(profile → account·instructorapplication).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final AccountJpaRepo accountRepo;
    private final StudentCertificateService studentCertificateService;

    public AccountProfileResponse myProfile(Account currentUser) {
        // @CurrentUser 는 detach 상태일 수 있어 LAZY(profilePhoto) 접근 위해 트랜잭션 안에서 재로드.
        Account account = accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);

        // 사람 표면 규칙 — 자기신고 수강생 레벨 + VERIFIED 강사 레벨, 그룹별 최고 1장, 정렬됨(CertificateBadgePolicy).
        // 강사 자격 표면(verifiedBadgesOf)이 아니다 — 수강생도 값이 온다(#330).
        List<AccountProfileResponse.CertBadge> certs = studentCertificateService.displayBadgesOf(account.getId()).stream()
                .map(b -> AccountProfileResponse.CertBadge.builder()
                        .disciplineCode(b.getDisciplineCode())
                        .organizationCode(b.getOrganizationCode())
                        .organizationOther(b.getOrganizationOther())
                        .level(b.getLevel())
                        .verified(b.isVerified())
                        .build())
                .collect(Collectors.toList());

        return AccountProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .nickName(account.getNickName())
                .roles(account.getRoles())
                .profilePhotoUrl(ProfilePhoto.displayUrlOf(account))
                .certs(certs)
                .build();
    }
}
