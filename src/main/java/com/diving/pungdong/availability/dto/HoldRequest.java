package com.diving.pungdong.availability.dto;

import lombok.*;

/**
 * 점유 추가 요청 — V2 "외부 예약 추가" 모달 / 캘린더 ± 빠른조정. 단일 hold 테이블에 row 1개 추가.
 *
 * <ul>
 *   <li>± 빠른조정 — {@code count=1}, {@code memo=null}.</li>
 *   <li>외부예약 — {@code count=1~N}, {@code memo="네이버 L1 홍길동"} 등.</li>
 * </ul>
 *
 * <p>{@code filled + count > capacity} 면 window 정원이 자동 확장된다(브리프 "정원 초과 시 자동 확장").
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class HoldRequest {

    /** 점유 인원 — 1 이상. */
    private int count;

    /** 외부예약 메모(선택). null/blank 면 ± 빠른조정으로 취급. */
    private String memo;
}
