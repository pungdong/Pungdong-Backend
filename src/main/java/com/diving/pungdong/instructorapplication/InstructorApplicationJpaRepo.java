package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstructorApplicationJpaRepo extends JpaRepository<InstructorApplication, Long> {

    Optional<InstructorApplication> findByAccount(Account account);

    Optional<InstructorApplication> findByAccountId(Long accountId);

    /** 어드민 대기 목록 — 상태별 조회. SUBMITTED 만 넘기면 승인/반려된 건은 빠진다. */
    Page<InstructorApplication> findAllByStatus(InstructorApplicationStatus status, Pageable pageable);

    /** 상태별 건수 — 어드민 탭 뱃지(검수중/통과/불통과)용. */
    long countByStatus(InstructorApplicationStatus status);
}
