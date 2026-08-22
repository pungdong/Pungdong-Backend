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

        // 인증마크 — VERIFIED 자격증에서(2026-08-22 수렴 전엔 승인 신청의 첨부). 형태는 v1 그대로.
        List<AccountProfileResponse.CertBadge> certs = studentCertificateService.verifiedBadgesOf(account.getId()).stream()
                .map(b -> AccountProfileResponse.CertBadge.builder()
                        .disciplineCode(b.getDisciplineCode())
                        .organizationCode(b.getOrganizationCode())
                        .organizationOther(b.getOrganizationOther())
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
