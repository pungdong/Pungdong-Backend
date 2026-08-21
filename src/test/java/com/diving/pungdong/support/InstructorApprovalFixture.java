package com.diving.pungdong.support;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 테스트에서 강사를 <b>정식 강사</b>로 만든다 — 그 종목의 승인(APPROVED) 신청을 심는다.
 *
 * <p><b>왜 필요해졌나</b>: 강의를 OPEN 하거나 학생에게 노출·판매하려면 <b>그 종목의 승인</b>이 필요하다
 * ({@code course.InstructorApprovalPolicy}). 그전까지 테스트들은 승인 없이 계정을 만들어 바로 코스를
 * OPEN 했는데, 그건 <b>실제로 뚫려 있던 구멍을 픽스처가 그대로 베끼고 있었던 것</b>이다 — 강사 신청을
 * 한 번도 안 한 계정도 강의를 팔 수 있었다.
 *
 * <p><b>여러 테스트가 공유하는 이유</b>: 각자 심으면 한쪽만 상태를 잘못 넣어도(예: {@code SUBMITTED})
 * 그 테스트만 조용히 다른 규칙을 검증하게 된다. 실제로 이관 전 {@code EnrollmentUseCaseTest} 는
 * {@code SUBMITTED} 를 심고 있었다 — 가용시간 게이트가 "신청 보유" 만 봐서 그것으로 충분했기 때문이다.
 *
 * <p>⚠️ <b>종목이 코스의 {@code disciplineCode} 와 같아야 한다.</b> 승인은 종목별이라
 * 프리다이빙 승인만으로는 스쿠버 코스가 열리지 않는다(그게 이 규칙의 요지다).
 */
public final class InstructorApprovalFixture {

    private InstructorApprovalFixture() {
    }

    /** 계정을 주어진 종목들의 정식 강사로 만든다. 이미 그 종목 신청이 있으면 승인으로 올린다. */
    public static void approve(InstructorApplicationJpaRepo repo, Account account, String... disciplineCodes) {
        for (String disciplineCode : disciplineCodes) {
            InstructorApplication application = repo
                    .findByAccountIdAndDisciplineCode(account.getId(), disciplineCode)
                    .orElseGet(() -> InstructorApplication.builder()
                            .account(account)
                            .disciplineCode(disciplineCode)
                            .build());
            application.setStatus(InstructorApplicationStatus.APPROVED);
            application.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(application);
        }
    }

    /** 대부분의 테스트가 쓰는 기본 종목. */
    public static void approveFreediving(InstructorApplicationJpaRepo repo, Account account) {
        approve(repo, account, "FREEDIVING");
    }
}
