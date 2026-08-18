package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.exception.*;
import com.diving.pungdong.global.security.UserAccount;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AuthProvider;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.account.dto.emailCheck.EmailResult;
import com.diving.pungdong.account.dto.nickNameCheck.NickNameRejectReason;
import com.diving.pungdong.account.dto.nickNameCheck.NickNameResult;
import com.diving.pungdong.account.dto.restore.AccountRestoreInfo;
import com.diving.pungdong.account.dto.signIn.SignInInfo;
import com.diving.pungdong.account.dto.signUp.SignUpInfo;
import com.diving.pungdong.account.dto.signUp.SignUpResult;
import com.diving.pungdong.account.dto.update.AccountUpdateInfo;
import com.diving.pungdong.account.dto.update.ForgotPasswordInfo;
import com.diving.pungdong.account.dto.update.NickNameInfo;
import com.diving.pungdong.account.dto.update.PasswordUpdateInfo;
import com.diving.pungdong.global.model.SuccessResult;
import com.diving.pungdong.global.validation.NickNamePolicy;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.EmailService;
import com.diving.pungdong.account.dto.read.AccountBasicInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Service
@RequiredArgsConstructor
@Transactional
public class AccountService implements UserDetailsService {
    private final AccountJpaRepo accountJpaRepo;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final ProfilePhotoService profilePhotoService;

    @Override
    public UserDetails loadUserByUsername(String id) throws UsernameNotFoundException {
        Account account = accountJpaRepo.findById(Long.valueOf(id)).orElseThrow(CUserNotFoundException::new);

        return new UserAccount(account);
    }

    public Account saveAccount(Account account) {
        return accountJpaRepo.save(account);
    }

    @Transactional(readOnly = true)
    public Account findAccountByEmail(String email) {
        Account account = accountJpaRepo.findByEmail(email).orElseThrow(CEmailSigninFailedException::new);
        if (account.getIsDeleted()) {
            throw new NoPermissionsException("계정이 삭제되었습니다.");
        }

        return account;
    }

    public Account findAccountById(Long id) {
        return accountJpaRepo.findById(id).orElseThrow(CUserNotFoundException::new);
    }

    public void checkDuplicationOfEmail(String email) {
        Optional<Account> account = accountJpaRepo.findByEmail(email);
        if (account.isPresent()) {
            throw new EmailDuplicationException();
        }
    }

    public void checkCorrectPassword(String password, Account account) {
        if (!passwordEncoder.matches(password, account.getPassword())) {
            throw new BadRequestException();
        }
    }

    public EmailResult checkEmailExistence(String email) {
        Boolean isExisted = accountJpaRepo.existsByEmail(email);

        return EmailResult.builder()
                .exists(isExisted)
                .build();
    }

    public Account saveAccountInfo(SignUpInfo signUpInfo) {
        checkReservedNickName(signUpInfo.getNickName());
        checkDuplicationOfNickName(signUpInfo.getNickName());
        checkDuplicationOfEmail(signUpInfo.getEmail());

        ProfilePhoto profilePhoto = profilePhotoService.saveDefaultProfilePhoto();

        Account student = Account.builder()
                .email(signUpInfo.getEmail())
                .password(passwordEncoder.encode(signUpInfo.getPassword()))
                .nickName(signUpInfo.getNickName())
                .provider(AuthProvider.EMAIL)
                .roles(Set.of(Role.STUDENT))
                .profilePhoto(profilePhoto)
                .build();
        return accountJpaRepo.save(student);
    }

    /** 가입 / 닉네임 변경 시 중복 가드 — 중복이면 throw (checkDuplicationOfEmail 와 대칭). */
    public void checkDuplicationOfNickName(String nickName) {
        if (accountJpaRepo.existsByNickName(nickName)) {
            throw new BadRequestException("닉네임이 중복되었습니다");
        }
    }

    /**
     * 가입 / 닉네임 변경 시 예약어 가드 — 예약어면 throw (형식은 DTO 의 {@code @Pattern} 이 이미 봤다).
     *
     * <p><b>왜 DTO 가 아니라 여기인가</b>: 예약어는 <b>어드민에게 열어 줘야</b> 한다(우리가 진짜 공식
     * 계정을 만들 때 쓰려고 막아 둔 이름이다). principal 을 아는 건 서비스뿐이라 판정 위치가 여기다.
     * 부수효과(계정 저장) 이전에 호출되므로 "검증은 side-effect 전" 원칙은 그대로 지켜진다.
     */
    public void checkReservedNickName(String nickName) {
        if (NickNamePolicy.isReserved(nickName)) {
            throw new BadRequestException(NickNamePolicy.RESERVED_MESSAGE);
        }
    }

    /**
     * 닉네임 중복확인 엔드포인트용 — <b>쓸 수 있는지</b>를 200 으로 반환 (checkEmailExistence 와 대칭).
     *
     * <p>중복뿐 아니라 형식·예약어까지 여기서 답한다. 안 그러면 FE 가 {@code exists:false} 만 보고
     * "사용 가능" 초록불을 켠 뒤 가입에서 400 을 맞는다 — 같은 판정을 두 곳이 다르게 하는 셈이다.
     * 기대된 부정 답이라 throw 하지 않는다(레포 규칙: check/query 는 반환, guard 는 throw).
     */
    public NickNameResult checkNickNameExistence(String nickName) {
        boolean isExisted = accountJpaRepo.existsByNickName(nickName);

        NickNameRejectReason reason = null;
        if (isExisted) {
            reason = NickNameRejectReason.DUPLICATED;
        } else if (!NickNamePolicy.isValidFormat(nickName)) {
            reason = NickNameRejectReason.FORMAT;
        } else if (NickNamePolicy.isReserved(nickName)) {
            reason = NickNameRejectReason.RESERVED;
        }

        return NickNameResult.builder()
                .exists(isExisted)
                .available(reason == null)
                .reason(reason)
                .build();
    }

    public AccountBasicInfo mapToAccountBasicInfo(Account account) {
        return AccountBasicInfo.builder()
                .id(account.getId())
                .email(account.getEmail())
                .nickName(account.getNickName())
                .birth(account.getBirth())
                .phoneNumber(account.getPhoneNumber())
                .gender(account.getGender())
                .roles(account.getRoles())
                .build();
    }

    public void updateAccountInfo(Account account, AccountUpdateInfo updateInfo) {
        account.setBirth(updateInfo.getBirth());
        account.setGender(updateInfo.getGender());
        account.setPhoneNumber(updateInfo.getPhoneNumber());

        accountJpaRepo.save(account);
    }

    /**
     * 닉네임 변경. 어드민은 예약어 가드를 건너뛴다 — 예약어를 막아 둔 목적 자체가 <b>우리가 나중에 쓰기
     * 위해서</b>라, 정작 우리가 못 쓰면 정책이 자기 목적을 배반한다. (형식 가드는 어드민에게도 그대로
     * 적용된다 — URL 식별자라 공백·특수문자는 어드민 계정이라고 안전해지지 않는다.)
     */
    public void updateNickName(Account account, String nickName) {
        if (!account.getRoles().contains(Role.ADMIN)) {
            checkReservedNickName(nickName);
        }
        checkDuplicationOfNickName(nickName);

        account.setNickName(nickName);
        accountJpaRepo.save(account);
    }

    public void updatePassword(Account account, PasswordUpdateInfo passwordUpdateInfo) {
        checkCorrectPassword(passwordUpdateInfo.getCurrentPassword(), account);

        account.setPassword(passwordEncoder.encode(passwordUpdateInfo.getNewPassword()));
        accountJpaRepo.save(account);
    }

    // 비밀번호 재확인 없이 탈퇴 — 호출자(컨트롤러)가 세션으로 본인을 이미 증명. 결정 히스토리는
    // docs/features/account-deletion.md. checkCorrectPassword 는 updatePassword 가 계속 사용한다.
    public void deleteAccount(Account account) {
        account.setIsDeleted(true);
        account.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        accountJpaRepo.save(account);
    }

    public Account updateAccountDeleted(AccountRestoreInfo accountRestoreInfo) {
        emailService.verifyAuthCode(accountRestoreInfo.getEmail(), accountRestoreInfo.getEmailAuthCode());

        Account account = accountJpaRepo.findByEmail(accountRestoreInfo.getEmail()).orElseThrow(ResourceNotFoundException::new);
        // 익명화가 끝난 계정은 복구 불가 — PII 가 이미 파기됐다(유예기간 경과). (findByEmail 도 익명화 후엔
        // 이메일이 deleted_*로 바뀌어 사실상 못 찾지만, 명시 가드로 의도를 분명히 한다.)
        if (account.getAnonymizedAt() != null) {
            throw new BadRequestException();
        }
        account.setIsDeleted(false);
        account.setDeletedAt(null);

        return account;
    }

    public void modifyForgetPassword(ForgotPasswordInfo forgotPasswordInfo) {
        String email = forgotPasswordInfo.getEmail();
        String authCode = forgotPasswordInfo.getAuthCode();
        emailService.verifyAuthCode(email, authCode);

        Account account = findAccountByEmail(email);

        account.setPassword(passwordEncoder.encode(forgotPasswordInfo.getNewPassword()));
        accountJpaRepo.save(account);
    }
}