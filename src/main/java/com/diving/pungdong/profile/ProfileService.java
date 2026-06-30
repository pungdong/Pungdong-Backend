package com.diving.pungdong.profile;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
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
    private final InstructorApplicationJpaRepo applicationRepo;

    public AccountProfileResponse myProfile(Account currentUser) {
        // @CurrentUser 는 detach 상태일 수 있어 LAZY(profilePhoto) 접근 위해 트랜잭션 안에서 재로드.
        Account account = accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);

        List<AccountProfileResponse.CertBadge> certs = applicationRepo
                .findByAccountIdAndStatus(account.getId(), InstructorApplicationStatus.APPROVED).stream()
                .flatMap(app -> app.getCertificates().stream()
                        .map(cert -> AccountProfileResponse.CertBadge.builder()
                                .disciplineCode(app.getDisciplineCode())
                                .organizationCode(cert.getOrganizationCode())
                                .organizationOther(cert.getOrganizationOther())
                                .build()))
                .collect(Collectors.toList());

        return AccountProfileResponse.builder()
                .id(account.getId())
                .email(account.getEmail())
                .nickName(account.getNickName())
                .roles(account.getRoles())
                .profilePhotoUrl(account.getProfilePhoto() == null ? null : account.getProfilePhoto().getImageUrl())
                .certs(certs)
                .build();
    }
}
