package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.exception.*;
import com.diving.pungdong.global.security.UserAccount;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AuthProvider;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.domain.lecture.Lecture;
import com.diving.pungdong.account.dto.emailCheck.EmailResult;
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
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.EmailService;
import com.diving.pungdong.account.dto.read.AccountBasicInfo;
import com.diving.pungdong.service.LectureService;
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

import java.time.LocalDateTime;
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
    private final LectureService lectureService;

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

    /** 닉네임 중복확인 엔드포인트용 — 중복 여부를 200 으로 반환 (checkEmailExistence 와 대칭). */
    public NickNameResult checkNickNameExistence(String nickName) {
        Boolean isExisted = accountJpaRepo.existsByNickName(nickName);

        return NickNameResult.builder()
                .exists(isExisted)
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

    public void updateNickName(Account account, String nickName) {
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
        account.setDeletedAt(LocalDateTime.now());
        Account updatedAccount = accountJpaRepo.save(account);

        lectureService.closeAllLecture(updatedAccount);
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