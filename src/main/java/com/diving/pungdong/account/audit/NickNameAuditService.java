package com.diving.pungdong.account.audit;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.validation.NickNamePolicy;
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

    private final NickNameAuditRepo repo;

    /**
     * 예약어 판정은 {@link NickNamePolicy} 단일 출처를 그대로 쓴다 — 가입/변경을 막는 규칙과 리포트가
     * 어긋나면 "지금 막히는 값"과 "이미 갖고 있는 값"의 집합이 달라져 진단으로 못 쓴다.
     *
     * <p>SQL {@code in (...)} 이 아니라 <b>자바에서 판정</b>하는 이유: 정책이 정확일치 말고
     * 부분일치·접두·정규화(구분자 제거·리트 치환)를 함께 보기 때문에 SQL 로 옮기면 규칙이 둘로 갈린다.
     * 계정 수가 적은 단계라 전건 스캔 비용보다 규칙 일치가 중요하다.
     */
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
                .reservedWordHolders(toAffected(repo.findAllWithNickName().stream()
                        .filter(account -> NickNamePolicy.isReserved(account.getNickName()))
                        .collect(Collectors.toList())))
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
