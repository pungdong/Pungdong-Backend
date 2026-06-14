package com.diving.pungdong.course;

/**
 * 코스 상품 상태 — 강사 "내 강의" 목록. <b>검수 없음</b>(강사 인증은 계정 단위라 강의별 검수 안 함, chat43).
 *
 * <ul>
 *   <li>{@code DRAFT} — 임시저장(비공개 작성 중)</li>
 *   <li>{@code OPEN} — 노출중(수강생이 신청 가능)</li>
 *   <li>{@code CLOSED} — 마감(노출 종료)</li>
 * </ul>
 */
public enum CourseStatus {
    DRAFT, OPEN, CLOSED
}
