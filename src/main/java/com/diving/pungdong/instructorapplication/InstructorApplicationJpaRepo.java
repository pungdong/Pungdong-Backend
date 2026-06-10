package com.diving.pungdong.instructorapplication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstructorApplicationJpaRepo extends JpaRepository<InstructorApplication, Long> {

    /** 종목별 신청 1건 — 제출/재제출 시 중복·대상 조회. (account_id, disciplineCode) UNIQUE. */
    Optional<InstructorApplication> findByAccountIdAndDisciplineCode(Long accountId, String disciplineCode);

    /** 내 신청 목록 (종목별 여러 건) — 최신순. GET /me 의 출처. */
    List<InstructorApplication> findByAccountIdOrderByIdDesc(Long accountId);

    /** 어드민 대기 목록 — 상태별 조회. SUBMITTED 만 넘기면 승인/반려된 건은 빠진다. */
    Page<InstructorApplication> findAllByStatus(InstructorApplicationStatus status, Pageable pageable);

    /** 상태별 건수 — 어드민 탭 뱃지(검수중/통과/불통과)용. */
    long countByStatus(InstructorApplicationStatus status);
}
