package com.diving.pungdong.certificate;

import com.diving.pungdong.account.event.AccountAnonymizedEvent;
import com.diving.pungdong.certificate.storage.StudentCertificatePhotoStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 PII 파기 수신부 — 익명화된 계정의 자격증 <b>행과 사진</b>을 모두 지운다.
 *
 * <p>자격증 사진은 실물 카드 촬영본이라 이름·자격증번호가 찍혀 있고, 행 자체도 보유 이력(PII)이다.
 * 결제 기록과 달리 <b>법정 보존 대상이 아니므로</b> 행까지 하드 삭제한다(account row 만 익명화로 남는다).
 *
 * <p><b>의존 방향</b>: certificate 가 account(의 이벤트)를 import — 허용 방향. account 는 이 패키지를
 * 모른다. 새 도메인이 파기에 참여하려면 account 를 건드리지 않고 리스너만 추가하면 된다.
 *
 * <h3>⚠️ 두 삭제의 실패 처리가 다르다 — 일부러다</h3>
 * 기본 {@code @EventListener} 는 <b>발행자(anonymize)의 트랜잭션 안에서 동기 실행</b>된다. 그래서:
 *
 * <ul>
 *   <li><b>행 삭제는 삼키지 않는다.</b> 자격증 <i>행 자체가 PII</i> 다. 실패를 catch 로 덮으면
 *       {@code anonymizedAt} 만 찍힌 채 PII 행이 남고, {@link
 *       com.diving.pungdong.account.AccountAnonymizationService} 의 멱등 가드가 <b>재시도까지 막는다</b>
 *       — 영구히 안 지워진다. 던져서 익명화 전체를 롤백시키고 다음 스윕에 다시 시도하게 하는 게 맞다.
 *       (게다가 같은 트랜잭션이라, JPA 예외를 catch 해도 rollback-only 마킹은 안 풀린다 —
 *       삼켜봐야 커밋 시점에 {@code UnexpectedRollbackException} 으로 터진다. "삼키면 계속 진행"은 거짓말이다.)</li>
 *   <li><b>사진 삭제만 best-effort.</b> 외부 스토리지 장애로 PII 파기가 무산되는 건 과하다 —
 *       실패해도 남는 건 <i>고아 객체 1개</i>고, 행은 이미 지워졌다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentCertificateAnonymizationListener {

    private final StudentCertificateJpaRepo certificateRepo;
    private final CertificateReviewJpaRepo reviewRepo;
    private final StudentCertificatePhotoStorage photoStorage;

    @EventListener
    public void onAccountAnonymized(AccountAnonymizedEvent event) {
        // 행 삭제는 **삼키지 않는다** — 실패하면 익명화 전체가 롤백돼야 한다.
        certificateRepo.deleteByOwnerId(event.accountId());
        reviewRepo.deleteByAccountId(event.accountId()); // 검수 큐/이력도 PII(누가 무엇을 올렸나)

        // 사진(외부 객체)만 best-effort. 이건 실패해도 고아 1개가 남을 뿐이다.
        try {
            photoStorage.deleteAllFor(event.accountId());
        } catch (RuntimeException e) {
            log.warn("[anonymize] account {} 자격증 사진 삭제 실패(행은 삭제됨, 고아 객체 잔존)", event.accountId(), e);
        }
    }
}
