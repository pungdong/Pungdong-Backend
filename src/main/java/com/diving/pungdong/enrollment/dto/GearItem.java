package com.diving.pungdong.enrollment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 대여 장비 1줄 (표시용 뷰) — {@code EnrollmentRoundEquipment} 스냅샷(name·size)의 <b>단일 투영</b>.
 * 강사 수강관리 hub · 학생 일정 hub · 강사 캘린더 신청자 행이 <b>모두 이 한 타입을 재사용</b>한다(같은 소스라
 * 형태가 갈라지면 안 됨 — 중복 정의 금지). {@code enrollment} 이 장비 스냅샷을 소유하므로 여기(enrollment/dto)가
 * 단일 출처이고, {@code availability} 는 이미 enrollment 를 읽기 전용 단방향 참조하므로 그대로 재사용한다.
 *
 * <p>{@code sizeLabel} 은 저장값("270"·"L") 그대로 — 단위("mm")·포맷은 FE 표기. 사이즈 없는 품목이면 null.
 */
@Getter
@Builder
public class GearItem {
    private final String name;
    private final String sizeLabel;
}
