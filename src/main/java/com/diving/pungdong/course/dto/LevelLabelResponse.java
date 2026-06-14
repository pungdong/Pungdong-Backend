package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.CertLevel;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

/**
 * 종목별 자격 레벨 표시 라벨 — 수강생 둘러보기 필터 칩 한 칸. 평탄화 코드({@code level})는 필터 쿼리값이고,
 * {@code label}(종목 무관 공통 단계명, 예 "레벨 1")에 {@code alias}(종목 통용 명칭, 예 "Open Water Diver")를
 * 덧붙여 입문자(단계)·경험자(명칭)를 동시에 만족시킨다. FE 는 {@code alias ? `${label} (${alias})` : label}.
 *
 * <p>강사 코스 작성 화면은 이걸 안 쓴다 — 거긴 이미 단체를 골랐으니 Sanity {@code displayName}(단체 공식명)을
 * 그대로 병기. 여기는 단체 무관(여러 단체 가로지르는 필터)이라 종목 공통 명칭을 쓴다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "levelLabels")
public class LevelLabelResponse {

    /** 평탄화 레벨 코드 — 필터 쿼리값(levels=...). */
    private CertLevel level;

    /** 종목 무관 공통 단계명(예: "레벨 1", "강사"). */
    private String label;

    /** 종목 통용 명칭(예: 스쿠버 "Open Water Diver"). 공통 명칭이 없는 종목(프리다이빙 등)은 null. */
    private String alias;
}
