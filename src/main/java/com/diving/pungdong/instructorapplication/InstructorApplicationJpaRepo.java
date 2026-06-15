package com.diving.pungdong.instructorapplication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstructorApplicationJpaRepo extends JpaRepository<InstructorApplication, Long> {

    /** 종목별 신청 1건 — 제출/재제출 시 중복·대상 조회. (account_id, disciplineCode) UNIQUE. */
    Optional<InstructorApplication> findByAccountIdAndDisciplineCode(Long accountId, String disciplineCode);

    /**
     * 그 종목 강사 트랙에 들어왔는가 — 신청 보유 여부(상태 무관, PENDING 포함). 커스텀 위치 생성 게이트.
     * 승인 전(리뷰 대기)에도 draft 준비를 막지 않는다는 정책 — 커스텀 위치는 비공개라 reject 돼도 노출 없음.
     */
    boolean existsByAccountIdAndDisciplineCode(Long accountId, String disciplineCode);

    /**
     * 강사 트랙에 들어왔는가 — 종목 무관, 신청 보유 여부(상태 무관, SUBMITTED 포함). 가용시간 캘린더
     * (availability) 진입 게이트. 가용시간은 종목별이 아니라 강사 단위 도구라 종목 조건이 없다.
     */
    boolean existsByAccountId(Long accountId);

    /** 내 신청 목록 (종목별 여러 건) — 최신순. GET /me 의 출처. */
    List<InstructorApplication> findByAccountIdOrderByIdDesc(Long accountId);

    /** 어드민 대기 목록 — 상태별 조회. SUBMITTED 만 넘기면 승인/반려된 건은 빠진다. */
    Page<InstructorApplication> findAllByStatus(InstructorApplicationStatus status, Pageable pageable);

    /** 상태별 건수 — 어드민 탭 뱃지(검수중/통과/불통과)용. */
    long countByStatus(InstructorApplicationStatus status);
}
