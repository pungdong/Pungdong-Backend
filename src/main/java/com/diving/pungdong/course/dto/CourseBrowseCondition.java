package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.venue.Region;
import lombok.*;

import java.util.List;

/**
 * 공개 둘러보기 필터 조건 — 수강생 메인 홈/필터 시트의 칩들을 그대로 옮긴 것. 모두 선택값(없으면 그 축은
 * 필터 안 걸림). 비-PII 라 GET 쿼리스트링으로 받는다.
 *
 * <p><b>종류·레벨은 평탄화 멀티칩(OR)</b>: 필터 시트는 [체험·L1·L2·L3·트레이닝]을 한 줄로 펼쳐 멀티선택,
 * 결과는 합집합이다(시안 {@code FILTER_LEVELS}). 코스 <i>작성</i> 화면의 계단식(종류 라디오 → 자격이면 레벨)과
 * 의도적으로 다름 — 둘러보기는 탐색 편의 우선이라 평탄. 체험·트레이닝은 {@code kinds}, 자격은 {@code levels}
 * 로 오고(필터엔 'CERTIFICATION' 칩 없음 — 자격은 레벨로 표현), BE 는 {@code (kind ∈ kinds) OR
 * (CERTIFICATION & level ∈ levels)} 로 묶는다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CourseBrowseCondition {

    /** 종목 코드(discipline.code) — <b>필수</b>. 화면이 종목별이라 항상 한 종목으로 좁힌다(누락은 컨트롤러 400). */
    private String disciplineCode;

    /** 제목 검색어(선택). */
    private String keyword;

    /** 지역 묶음(선택, 생략=전체). */
    private Region region;

    /** 코스 종류 칩(체험·트레이닝) — 멀티. 필터엔 CERTIFICATION 칩 없음(자격은 levels 로). */
    private List<CourseKind> kinds;

    /** 자격 레벨 칩(L1·L2·L3) — 멀티. kinds 와 OR 합집합. */
    private List<CertLevel> levels;

    /** 자격증 단체 코드(AIDA/PADI/SSI…). '상관없음' = 생략. */
    private List<String> organizationCodes;

    /** 가격 밴드 하한(원, 포함). */
    private Integer minPrice;
    /** 가격 밴드 상한(원, 포함). */
    private Integer maxPrice;

    /** 정렬 — 기본 LATEST. */
    private Sort sort;

    /**
     * 정렬 화이트리스트. {@code 인기순}/{@code 가까운 일정}은 코스에 평점·확정일정 신호가 아직 없어 후속
     * (부킹·리뷰 도입 시 추가). 현재는 최신/가격만.
     */
    public enum Sort {
        LATEST, PRICE_ASC, PRICE_DESC
    }
}
