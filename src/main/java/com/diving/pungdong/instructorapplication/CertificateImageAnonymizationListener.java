package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.event.AccountAnonymizedEvent;
import com.diving.pungdong.instructorapplication.storage.CertificateImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 PII 파기 수신부 — 익명화된 계정의 <b>자격증 이미지</b>를 저장소에서 지운다.
 *
 * <p><b>왜 필요했나</b>: 자격증 이미지는 실명·자격증번호가 찍힌 개인정보다.
 * {@code docs/features/image-storage-and-serving.md} §2 는 이미지를 {@code instructorCertificate/{accountId}/}
 * 로 <b>회원별 그룹핑</b>하는 근거를 "탈퇴 PII 익명화 시 prefix 일괄 삭제"라고 적어놨지만 <b>그 삭제가 구현된
 * 적이 없었다</b> — 같은 문서 §4b 가 "실버그 / 개인정보 파기 의무 위반"으로 기록한 프로필 사진 사고와 동일한
 * 구멍이 자격증 쪽에 그대로 남아 있었다.
 *
 * <p><b>의존 방향</b>: instructorapplication 이 account(의 이벤트)를 import 한다 — 허용 방향
 * (account→instructorapplication 역참조 아님).
 *
 * <p><b>best-effort</b>: 동기 리스너라 여기서 던지면 익명화 트랜잭션이 통째로 롤백된다. 스토리지 실패로
 * PII 파기 자체가 무산되는 게 더 나쁘므로 삼키고 로그만 남긴다(고아 객체는 재시도/수동 정리 대상).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateImageAnonymizationListener {

    private final CertificateImageStorage certificateImageStorage;

    @EventListener
    public void onAccountAnonymized(AccountAnonymizedEvent event) {
        try {
            certificateImageStorage.deleteAllFor(event.accountId());
        } catch (RuntimeException e) {
            log.warn("[anonymize] account {} 자격증 이미지 삭제 실패(익명화는 계속 진행)", event.accountId(), e);
        }
    }
}
