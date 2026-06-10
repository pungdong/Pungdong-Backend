package com.diving.pungdong.discipline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisciplineJpaRepo extends JpaRepository<Discipline, Long> {

    Optional<Discipline> findByCode(String code);

    boolean existsByCode(String code);

    /** 활성 종목만 정렬 순서대로 — 홈 셀렉터 / 신청 종목 선택용. */
    List<Discipline> findAllByActiveTrueOrderBySortOrderAsc();
}
