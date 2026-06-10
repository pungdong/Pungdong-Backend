package com.diving.pungdong.discipline;

import com.diving.pungdong.discipline.dto.DisciplineResponse;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 종목 조회 + 검증. 강사 신청이 종목 코드의 유효성과 {@code requiresCertification}(자격증 필수
 * 여부)을 여기서 확인한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisciplineService {

    private final DisciplineJpaRepo disciplineRepo;

    public List<DisciplineResponse> getActiveDisciplines() {
        return disciplineRepo.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(DisciplineResponse::from)
                .collect(Collectors.toList());
    }

    /** 활성 종목 1건 조회 — 없거나 비활성이면 400. (강사 신청 검증용) */
    public Discipline getActiveByCode(String code) {
        Discipline discipline = disciplineRepo.findByCode(code)
                .orElseThrow(BadRequestException::new);
        if (!discipline.isActive()) {
            throw new BadRequestException();
        }
        return discipline;
    }
}
