package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.DeviceType;
import com.diving.pungdong.account.FirebaseToken;
import com.diving.pungdong.account.FirebaseTokenJpaRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
                        .lastSeenAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build()));
    }

    @Transactional
    public void unregister(Account account, String token) {
        firebaseTokenJpaRepo.deleteByAccountAndToken(account, token);
    }
}
