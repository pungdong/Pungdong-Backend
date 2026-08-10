package com.diving.pungdong.account;

import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.dto.profilePhoto.ProfilePhotoInfo;
import com.diving.pungdong.dto.profilePhoto.ProfilePhotoUpdateInfo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePhotoService {
    private final ProfilePhotoJpaRepo profilePhotoJpaRepo;
    private final S3Uploader s3Uploader;

    @Transactional
    public ProfilePhoto saveDefaultProfilePhoto() {
        ProfilePhoto profilePhoto = ProfilePhoto.builder()
                .imageUrl(ProfilePhoto.DEFAULT_IMAGE_URL)
                .build();

        return profilePhotoJpaRepo.save(profilePhoto);
    }

    @Transactional(readOnly = true)
    public ProfilePhoto findByProfilePhotoId(Long profilePhotoId) {
        return profilePhotoJpaRepo.findById(profilePhotoId).orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional
    public ProfilePhotoUpdateInfo updateProfilePhoto(Account account, MultipartFile image) throws IOException {
        ProfilePhoto profilePhoto = findByProfilePhotoId(account.getProfilePhoto().getId());
        String previousUrl = profilePhoto.getImageUrl();

        String fileUri = s3Uploader.uploadPublic(image, "profile-photo");
        profilePhoto.setImageUrl(fileUri);

        // 교체된 옛 사진은 아무도 참조하지 않는다 — 안 지우면 S3 고아로 쌓인다. 업로드 성공 뒤에만
        // 지워서, 삭제가 실패해도 새 사진은 이미 저장된 상태를 유지한다(고아 1개 < 사진 유실).
        // 공유 기본 이미지는 특정 개인의 것이 아니므로 절대 지우지 않는다.
        if (previousUrl != null && !ProfilePhoto.DEFAULT_IMAGE_URL.equals(previousUrl)
                && !previousUrl.equals(fileUri)) {
            try {
                s3Uploader.deletePublicObject(previousUrl);
            } catch (RuntimeException e) {
                log.warn("[profile-photo] 옛 사진 S3 삭제 실패(계속 진행) url={}", previousUrl, e);
            }
        }

        account.setProfilePhoto(profilePhoto);

        return ProfilePhotoUpdateInfo.builder()
                .profilePhotoId(profilePhoto.getId())
                .url(profilePhoto.getImageUrl())
                .build();
    }

    @Transactional(readOnly = true)
    public ProfilePhotoInfo findByAccount(Account account) {
        ProfilePhoto profilePhoto = findByProfilePhotoId(account.getProfilePhoto().getId());

        return ProfilePhotoInfo.builder()
                .profilePhotoId(profilePhoto.getId())
                .imageUrl(profilePhoto.getImageUrl())
                .build();
    }
}
