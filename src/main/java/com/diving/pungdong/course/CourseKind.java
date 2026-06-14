package com.diving.pungdong.course;

/**
 * 코스 종류 — 자격 레벨(CertLevel)을 쓰는지 가른다. 디자인의 레벨 칩(체험·L1~·트레이닝) 중 체험/트레이닝은
 * 자격증이 아니라 코스 종류라, cert 레벨과 섞지 않고 여기서 분리한다.
 *
 * <ul>
 *   <li>{@code TRIAL} — 체험 (자격 없음)</li>
 *   <li>{@code CERTIFICATION} — 자격 과정 ({@code levels} 필수: 단체 + 평탄화 레벨)</li>
 *   <li>{@code TRAINING} — 트레이닝/보충 (자격 없음)</li>
 * </ul>
 */
public enum CourseKind {
    TRIAL, CERTIFICATION, TRAINING
}
