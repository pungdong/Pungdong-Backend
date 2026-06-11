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
     * 한 화면에서 체크한 약관들을 동의 이력으로 기록.
     *
     * <p><b>버전은 BE 가 정한다</b> — 각 약관의 현재 버전을 Sanity 에서 key 로 직접 조회하고,
     * 클라이언트가 보낸 version 은 "화면에서 본 버전" 으로 간주해 현재 버전과 <b>일치하는지만</b>
     * 검증한다. 다르면(옛/위조 버전, 또는 세션 중 약관 개정) 400 → FE 가 재확인 후 재동의.
     * 기록·박제에 쓰는 version 은 절대 클라이언트 값이 아니라 Sanity 의 현재 값이다.
     */
    @Transactional
    public List<AgreementRef> record(Account account, RecordConsentRequest request) {
        Account managed = accountRepo.findById(account.getId())
                .orElseThrow(ResourceNotFoundException::new);

        List<AgreementRef> recorded = new ArrayList<>();
        for (AgreementRef ref : request.getAgreements()) {
            SanityTermClient.FetchedTerm current = sanityTermClient.fetchCurrentTerm(ref.getKey())
                    .orElseThrow(BadRequestException::new);   // 없는/비활성 약관

            if (!current.version().equals(ref.getVersion())) {
                // 클라이언트가 본 버전 ≠ 현재 버전 — 다운그레이드/위조 또는 세션 중 개정
                throw new BadRequestException("약관이 갱신되었습니다. 다시 확인 후 동의해 주세요.");
            }

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
