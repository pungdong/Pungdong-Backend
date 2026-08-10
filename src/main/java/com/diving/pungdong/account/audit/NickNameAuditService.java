package com.diving.pungdong.account.audit;

import com.diving.pungdong.account.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 닉네임 사전 점검 — <b>읽기 전용</b>. {@code @Transactional(readOnly = true)} 로 쓰기 가능성을 구조적으로
 * 막았고, 레포지토리에도 쓰기 메서드가 없다.
 *
 * <p>런너와 분리한 이유: 런너는 부팅 시 한 번 로그를 찍는 껍데기라 테스트하기 나쁘고, 판정 로직(무엇을
 * 중복으로 볼 것인가·무엇이 예약어인가)이 진짜 검증 대상이라서다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NickNameAuditService {

    /**
     * 예약어 — 공개 프로필 경로({@code /instructors/{nickName}})와 충돌하거나 충돌할 여지가 있는 값들.
     * {@code public} 은 <b>실제로</b> 기존 {@code GET /instructors/public} 리터럴과 부딪힌다(Spring 이
     * 리터럴을 우선하므로 그 닉네임의 프로필은 영영 안 열린다). 나머지는 앞으로 이 네임스페이스에 생길
     * 만한 경로를 미리 막아두는 것.
     */
    public static final List<String> RESERVED_NICKNAMES = List.of(
            "public", "me", "admin", "new", "search", "about", "help", "login", "signup", "api", "docs");

    private final NickNameAuditRepo repo;

    public NickNameAudit run() {
        List<NickNameAudit.DuplicateGroup> duplicates = repo.findDuplicatedNickNames().stream()
                .map(normalized -> NickNameAudit.DuplicateGroup.builder()
                        .normalizedNickName(normalized)
                        .accountIds(repo.findAccountIdsByNormalizedNickName(normalized))
                        .build())
                .collect(Collectors.toList());

        return NickNameAudit.builder()
                .totalAccounts(repo.countAll())
                .nullNickNames(repo.countNullNickNames())
                .duplicates(duplicates)
                .reservedWordHolders(toAffected(repo.findByReservedNickNames(RESERVED_NICKNAMES)))
                .urlUnsafeHolders(toAffected(repo.findUrlUnsafeNickNames()))
                .build();
    }

    private List<NickNameAudit.AffectedAccount> toAffected(List<Account> accounts) {
        return accounts.stream()
                .map(account -> NickNameAudit.AffectedAccount.builder()
                        .accountId(account.getId())
                        .nickName(account.getNickName())
                        .deleted(Boolean.TRUE.equals(account.getIsDeleted()))
                        .build())
                .collect(Collectors.toList());
    }
}
