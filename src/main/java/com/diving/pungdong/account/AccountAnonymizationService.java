package com.diving.pungdong.account;

import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 탈퇴(soft delete) 후 유예기간이 지난 계정의 PII 를 파기(익명화)한다.
 *
 * <p>왜 하드 삭제가 아니라 익명화인가: 결제·계약 기록은 전자상거래법상 5년 보존 의무라
 * {@code account} row 를 지울 수 없다(FK 무결성). 그래서 식별정보(이메일·전화·이름·생일·성별·
 * 비밀번호·프로필사진·푸시토큰)만 파기하고 row 와 법정 보존 기록은 남긴다. 정책·보존 항목·법적
 * 근거는 docs/features/account-deletion.md.
 *
 * <p>유예기간(grace) 동안은 PII 를 복구용으로 보유하고(복구 = {@link AccountService#updateAccountDeleted}),
 * 경과 후 이 서비스가 익명화한다. {@link Account#getAnonymizedAt()} 가 멱등 가드 — 이미 익명화된
 * 계정은 건너뛰므로 스케줄러가 중복/재시도로 돌아도 안전하다(ECS 멀티태스크 가정).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAnonymizationService {

    private final AccountJpaRepo accountJpaRepo;
    private final FirebaseTokenJpaRepo firebaseTokenJpaRepo;
    private final ProfilePhotoJpaRepo profilePhotoJpaRepo;
    private final S3Uploader s3Uploader;
    private final PasswordEncoder passwordEncoder;

    /** 탈퇴 후 PII 를 보유하는 유예기간(일). 기본 30일 — docs/features/account-deletion.md. */
    @Value("${pungdong.account.deletion.grace-days:30}")
    private long graceDays;

    /** 익명화 대상 id(탈퇴 + 유예 경과 + 미익명화). 읽기 전용. */
    @Transactional(readOnly = true)
    public List<Long> findDueAccountIds(LocalDateTime now) {
        return accountJpaRepo.findIdsToAnonymize(now.minusDays(graceDays));
    }

    /**
     * 한 계정을 익명화한다 — <b>각 건 독립 트랜잭션</b>(외부 호출이라 프록시 경유). 멱등:
     * 이미 익명화됐거나 탈퇴 상태가 아니면 no-op.
     */
    @Transactional
    public void anonymize(Long accountId) {
        Account account = accountJpaRepo.findById(accountId).orElse(null);
        if (account == null || !Boolean.TRUE.equals(account.getIsDeleted()) || account.getAnonymizedAt() != null) {
            return; // 멱등 가드 — 중복/재시도 안전
        }

        // 프로필 사진(얼굴 = PII): 공유 기본 이미지가 아니면 S3 객체 삭제 후 행 제거.
        ProfilePhoto photo = account.getProfilePhoto();
        if (photo != null) {
            if (photo.getImageUrl() != null && !ProfilePhoto.DEFAULT_IMAGE_URL.equals(photo.getImageUrl())) {
                try {
                    s3Uploader.deleteFileFromS3(photo.getImageUrl());
                } catch (RuntimeException e) {
                    log.warn("[anonymize] account {} 프로필사진 S3 삭제 실패(계속 진행)", accountId, e);
                }
            }
            account.setProfilePhoto(null);
            profilePhotoJpaRepo.delete(photo);
        }

        // 푸시 토큰(기기 식별자) 전량 제거.
        firebaseTokenJpaRepo.deleteByAccount_Id(accountId);

        // 식별정보 파기 — 이메일은 유니크 슬롯을 비워 재가입을 막지 않도록 결정적 placeholder 로.
        account.setEmail("deleted_" + accountId + "@deleted.local");
        account.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 로그인 불가
        account.setNickName(null);
        account.setPhoneNumber(null);
        account.setBirth(null);
        account.setGender(null);
        account.setSelfIntroduction(null);
        account.setSocialId(null);
        account.setAnonymizedAt(LocalDateTime.now());

        accountJpaRepo.save(account);
        log.info("[anonymize] account {} PII 파기 완료", accountId);
    }
}
