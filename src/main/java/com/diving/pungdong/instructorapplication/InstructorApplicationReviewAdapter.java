package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.certificate.InstructorApplicationReviewPort;
import com.diving.pungdong.certificate.dto.ApplicationReviewView;
import com.diving.pungdong.instructorapplication.dto.InstructorApplicationDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검수 큐(NEW 행)가 강사 신청에 맡기는 일 — 승인/반려는 {@link InstructorApplicationService} 에 그대로 위임한다
 * (권한 부여·Rule B 호출이 거기 있다). {@code InstructorApprovalLookupAdapter} 와 따로 둔 이유: 이 어댑터는
 * 서비스를 끌어오므로 검증 서비스 쪽에서 쓰면 순환이 된다. 검수 큐 서비스만 이걸 쓴다.
 */
@Component
@RequiredArgsConstructor
public class InstructorApplicationReviewAdapter implements InstructorApplicationReviewPort {

    private final InstructorApplicationService applicationService;
    private final InstructorApplicationJpaRepo applicationRepo;

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicationReviewView> view(Long applicationId) {
        return applicationRepo.findById(applicationId).map(application -> {
            InstructorApplicationDetail d = applicationService.getApplicationDetail(applicationId);
            return ApplicationReviewView.builder()
                    .applicationId(d.getApplicationId())
                    .status(d.getStatus().name())
                    .certificateIds(new ArrayList<>(application.getCertificateIds()))
                    .insuranceFileKey(d.getInsuranceFileKey())
                    .insuranceViewUrl(d.getInsuranceViewUrl())
                    .realName(d.getRealName())
                    .birth(d.getBirth())
                    .phoneNumber(d.getPhoneNumber())
                    .rejectionReason(d.getRejectionReason())
                    .createdAt(d.getCreatedAt())
                    .submittedAt(d.getSubmittedAt())
                    .reviewedAt(d.getReviewedAt())
                    .reviewerNickName(d.getReviewerNickName())
                    .build();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> certificateIdsOf(Collection<Long> applicationIds) {
        Map<Long, List<Long>> out = new java.util.HashMap<>();
        for (Object[] row : applicationRepo.findCertificateIdsByApplicationIds(applicationIds)) {
            out.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>())
                    .add(((Number) row[1]).longValue());
        }
        return out;
    }

    @Override
    public void approve(Long applicationId, Account reviewer) {
        applicationService.approve(applicationId, reviewer);
    }

    @Override
    public void reject(Long applicationId, Account reviewer, String reason) {
        applicationService.reject(applicationId, reviewer, reason);
    }
}
