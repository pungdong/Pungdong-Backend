package com.diving.pungdong.availability.dto;

import com.diving.pungdong.enrollment.dto.GearItem;
import lombok.*;

import java.util.List;

/**
 * 슬롯 안 학생 1명 요약 — V2 디자인의 슬롯 신청자 행(이름 · 단체·레벨 · 대여 장비). 강사측 수강관리
 * (enrollment) day 캘린더 이벤트 블록과 통일된 어휘.
 *
 * <p><b>단체·레벨은 평탄 3종으로 내린다</b>({@code organizationCode}/{@code disciplineCode}/{@code levels}).
 * 이게 BE↔Sanity 공유 키 — FE 가 이 셋으로 Sanity cert 카탈로그(`certificationsByOrgAndDiscipline`)에서
 * 그 단체의 표시명(예: PADI·SCUBA·LEVEL_1 → "Open Water Diver")을 룩업해 상세에 옷 입힌다. BE 는 단체별
 * 명칭을 알 필요 없음(cert 카탈로그는 FE-direct CDN — sanity_read_principle). 리스트는 같은 값으로 평탄
 * 포맷("AIDA · L2"). 신청한 <b>코스의 목표 레벨</b>이며 학생 본인 자격 레벨은 아니다(미수집).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ApplicantSummaryResponse {

    /** 학생 이름. */
    private String name;

    /** 발급 단체 코드(예: AIDA/PADI/MOLCHANOVS). 코스에 단체 미지정(기타·직접입력)이면 null. */
    private String organizationCode;

    /** 종목 코드(FREEDIVING/SCUBA/…). 코스 없는 행이면 null. */
    private String disciplineCode;

    /** 코스 목표 자격 레벨(평탄: LEVEL_1~4/INSTRUCTOR/INSTRUCTOR_TRAINER). 범위 코스면 여러 개. */
    private List<String> levels;

    /** 이번 세션 대여 장비 내역(이름 + 선택 사이즈). 강사 hub gearItems / 학생 hub gearItems 와 동일 형태. 없으면 빈 배열. */
    private List<GearItem> gear;

    /** 'external' 이면 외부 플랫폼 점유 행, null 이면 풍덩 학생. */
    private String kind;
}
