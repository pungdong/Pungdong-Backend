package com.diving.pungdong.ota;

import com.diving.pungdong.account.event.AccountAnonymizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 PII 파기 수신부 — 익명화된 계정의 <b>기기 링크만 끊고 행은 남긴다.</b>
 *
 * <p><b>왜 다른 도메인처럼 하드 삭제하지 않나</b>: {@code ota_device} 행은 PII 가 아니다. 담긴 건 설치
 * 식별자(우리가 만든 난수)·앱 버전·번들 id·지문 같은 <b>기기 통계</b>뿐이고, 사람을 가리키는 건
 * {@code account_id} 하나다. 그 하나만 끊으면 개인정보는 사라지고, 통계는 남는다.
 *
 * <p>행까지 지우면 <b>릴리스 대시보드에 구멍이 난다</b> — 그 기기가 어느 번들을 실행 중인지가 사라져
 * "잘못된 번들이 나갔을 때 몇 명이 어디 있나"의 분모가 조용히 줄어든다. 탈퇴는 앱 삭제와 다르므로
 * 그 설치는 여전히 그 번들을 돌리고 있을 수 있다.
 *
 * <p><b>의존 방향</b>: ota 가 account(의 이벤트)를 import — 허용 방향. account 는 이 패키지를 모른다.
 *
 * <p>기본 {@code @EventListener} 는 발행자(anonymize)의 트랜잭션 안에서 동기 실행된다. 여기선
 * <b>삼키지 않는다</b> — 링크 해제가 실패하면 익명화 전체가 롤백돼 다음 스윕에 다시 시도하는 게 맞다
 * ({@code AccountAnonymizationService} 의 멱등 가드가 재시도를 막으므로, 삼키면 링크가 영구히 남는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtaDeviceAnonymizationListener {

    private final OtaDeviceJpaRepo otaDeviceRepo;

    @EventListener
    public void onAccountAnonymized(AccountAnonymizedEvent event) {
        int unlinked = otaDeviceRepo.unlinkAccount(event.accountId());
        if (unlinked > 0) {
            log.info("[anonymize] account {} OTA 기기 링크 {}건 해제(행은 유지)", event.accountId(), unlinked);
        }
    }
}
