package com.diving.pungdong.service.account;

import com.diving.pungdong.domain.account.Account;
import com.diving.pungdong.domain.account.DeviceType;
import com.diving.pungdong.domain.account.FirebaseToken;
import com.diving.pungdong.repo.FirebaseTokenJpaRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FirebaseTokenService {

    private final FirebaseTokenJpaRepo firebaseTokenJpaRepo;

    @Transactional
    public void register(Account account, String token, DeviceType deviceType) {
        firebaseTokenJpaRepo.findByToken(token).ifPresentOrElse(
                existing -> existing.markSeenBy(account),
                () -> firebaseTokenJpaRepo.save(FirebaseToken.builder()
                        .token(token)
                        .account(account)
                        .deviceType(deviceType)
                        .lastSeenAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .build()));
    }

    @Transactional
    public void unregister(Account account, String token) {
        firebaseTokenJpaRepo.deleteByAccountAndToken(account, token);
    }
}
