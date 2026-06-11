package com.diving.pungdong.consent;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.consent.dto.AgreementRef;
import com.diving.pungdong.consent.dto.MyConsentResponse;
import com.diving.pungdong.consent.dto.RecordConsentRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 동의(consent) 도메인 서비스 — 화면에서 받은 약관 동의를 이력으로 남긴다.
 *
 * <p>핵심: 약관 전문은 유저별로 복사하지 않고 {@link AgreementTermArchive}(버전당 1행)를
 * 참조한다. 어떤 버전에 <b>처음</b> 동의가 들어오면 Sanity 에서 전문을 받아 freeze 하고
 * ({@link #freeze}), 이후 동의는 그 박제 행을 재사용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsentService {

    private final SanityTermClient sanityTermClient;
    private final AgreementTermArchiveJpaRepo archiveRepo;
    private final ConsentJpaRepo consentRepo;
    private final AccountJpaRepo accountRepo;

    /**
     * 한 화면에서 체크한 약관들(key)을 동의 이력으로 기록.
     *
     * <p><b>버전은 BE 가 전적으로 정한다</b> — 각 key 로 Sanity 의 현재 활성 버전을 조회해 그
     * 버전으로 박제·기록한다. FE 는 version 을 보내지 않는다(계약에 없음) → 옛/위조 버전으로
     * 기록될 여지 자체가 없다. 기록된 version 은 반환값으로 알려준다.
     */
    @Transactional
    public List<AgreementRef> record(Account account, RecordConsentRequest request) {
        Account managed = accountRepo.findById(account.getId())
                .orElseThrow(ResourceNotFoundException::new);

        List<AgreementRef> recorded = new ArrayList<>();
        for (String key : request.getKeys()) {
            SanityTermClient.FetchedTerm current = sanityTermClient.fetchCurrentTerm(key)
                    .orElseThrow(BadRequestException::new);   // 없는/비활성 약관

            AgreementTermArchive archive = archiveRepo
                    .findByTermKeyAndVersion(current.key(), current.version())
                    .orElseGet(() -> freeze(current));

            consentRepo.save(Consent.builder()
                    .account(managed)
                    .agreementTerm(archive)
                    .context(request.getContext())
                    .agreedAt(LocalDateTime.now())
                    .build());

            recorded.add(AgreementRef.builder()
                    .key(archive.getTermKey())
                    .version(archive.getVersion())
                    .build());
        }
        return recorded;
    }

    /**
     * 현재 약관 버전을 BE DB 에 불변 박제 (이미 Sanity 에서 받아온 전문 그대로).
     *
     * <p>동시에 같은 새 버전을 박제하는 극히 드문 경합은 UNIQUE 제약이 막고 500 이 난다 →
     * FE 재시도 시 이미 존재하므로 성공. (MVP 허용 — 출시 시 동시성 미미.)
     */
    private AgreementTermArchive freeze(SanityTermClient.FetchedTerm term) {
        return archiveRepo.save(AgreementTermArchive.builder()
                .termKey(term.key())
                .version(term.version())
                .title(term.title())
                .body(term.body())
                .required(term.required())
                .archivedAt(LocalDateTime.now())
                .build());
    }

    /** 내 동의 이력 (최신순). 각 항목은 박제된 약관의 key/version/title 을 보여준다. */
    public List<MyConsentResponse> getMyConsents(Account account) {
        return consentRepo.findByAccountIdOrderByIdDesc(account.getId()).stream()
                .map(c -> MyConsentResponse.builder()
                        .key(c.getAgreementTerm().getTermKey())
                        .version(c.getAgreementTerm().getVersion())
                        .title(c.getAgreementTerm().getTitle())
                        .context(c.getContext())
                        .agreedAt(c.getAgreedAt())
                        .build())
                .collect(Collectors.toList());
    }
}
