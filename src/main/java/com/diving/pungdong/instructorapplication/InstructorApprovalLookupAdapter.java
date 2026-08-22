package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.certificate.InstructorApprovalLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code certificate} 도메인의 질문("승인된 강사인가")에 이 도메인이 답한다 — 의존 방향을
 * instructorapplication → certificate 한쪽으로 유지하기 위한 어댑터.
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
}
