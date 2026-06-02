package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.ProfilePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfilePhotoJpaRepo extends JpaRepository<ProfilePhoto, Long> {
}
