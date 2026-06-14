package com.diving.pungdong.course;

/**
 * 평탄화된 자격증 레벨 — 단체마다 명칭(예: "Advanced Freediver", "AIDA 2")은 달라도 이 6종 사다리로
 * 정규화한다. 단체별 명칭(`displayName`)은 Sanity {@code certOrganization.certifications[]} 가 소유하고
 * (FE 가 직접 읽음), BE 는 이 평탄화 코드만 enum 으로 저장/비교한다.
 *
 * <p>계약: Sanity {@code certification.level} ↔ 이 enum ↔ {@code types.ts} 의 {@code CertLevel} union.
 * 셋 중 하나를 바꾸면 나머지도 함께 (sanity/CLAUDE.md "계약").
 *
 * <p>{@code INSTRUCTOR_TRAINER} = 강사를 길러내는 상위 등급(예: Course Director, Instructor Trainer).
 * {@code INSTRUCTOR} 위 한 칸. 코스 작성(목표 레벨)과 강사 신청(본인 레벨, 향후) 두 곳이 이 카탈로그를 공유한다.
 */
public enum CertLevel {
    LEVEL_1("레벨 1"),
    LEVEL_2("레벨 2"),
    LEVEL_3("레벨 3"),
    LEVEL_4("레벨 4"),
    INSTRUCTOR("강사"),
    INSTRUCTOR_TRAINER("강사 양성");

    /** 종목 무관 공통 단계명(평탄화 한글). 종목별 통용 명칭은 {@link CertLevelLabels} 가 alias 로 덧붙인다. */
    private final String displayName;

    CertLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
