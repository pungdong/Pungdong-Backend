package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.certificate.InstructorApprovalLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code certificate} 도메인의 질문("승인된 강사인가")에 이 도메인이 답한다 — 의존 방향을
 * instructorapplication → certificate 한쪽으로 유지하기 위한 어댑터. 레포만 쓴다(서비스를 끌면
 * InstructorApplicationService → CertificateVerificationService → 이 어댑터 → … 순환).
 */
@Component
@RequiredArgsConstructor
public class InstructorApprovalLookupAdapter implements InstructorApprovalLookup {

    private final InstructorApplicationJpaRepo applicationRepo;

    @Override
    public boolean isApprovedInstructor(Long accountId, String disciplineCode) {
        return applicationRepo.existsByAccountIdAndDisciplineCodeAndStatus(
                accountId, disciplineCode, InstructorApplicationStatus.APPROVED);
    }

    @Override
    public Map<Long, Set<String>> approvedDisciplinesOf(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return applicationRepo.findByAccountIdInAndStatus(accountIds, InstructorApplicationStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(a -> a.getAccount().getId(),
                        Collectors.mapping(InstructorApplication::getDisciplineCode,
                                Collectors.toCollection(HashSet::new))));
    }
}
