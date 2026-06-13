package com.diving.pungdong.course;

/**
 * 평탄화된 자격증 레벨 — 단체마다 명칭(예: "Advanced Freediver", "AIDA 2")은 달라도 이 5종 사다리로
 * 정규화한다. 단체별 명칭(`displayName`)은 Sanity {@code certOrganization.certifications[]} 가 소유하고
 * (FE 가 직접 읽음), BE 는 이 평탄화 코드만 enum 으로 저장/비교한다.
 *
 * <p>계약: Sanity {@code certification.level} ↔ 이 enum ↔ {@code types.ts} 의 {@code CertLevel} union.
 * 셋 중 하나를 바꾸면 나머지도 함께 (sanity/CLAUDE.md "계약").
 *
 * <p>코스 작성(목표 레벨)과 강사 신청(본인 레벨, 향후) 두 곳이 이 카탈로그를 공유한다.
 */
public enum CertLevel {
    LEVEL_1,
    LEVEL_2,
    LEVEL_3,
    LEVEL_4,
    INSTRUCTOR
}
