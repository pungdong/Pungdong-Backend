package com.diving.pungdong.global.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code -1015 SESSION_TIME_OVERLAP} 400 body — 공통 실패 envelope({@code success/code/msg})에
 * <b>겹친 기존 일정 목록</b>을 더한다. FE 가 "○○ 14:00–16:00 일정과 겹칩니다" 처럼 무엇과 부딪혔는지
 * 안내하고, 강사 캘린더에선 그 일정으로 이동할 수 있게 하려는 것({@code RateLimitedResult} 와 같은 확장 방식).
 *
 * <p>학생 경로(신청·일정변경·제안 선택)에서도 같은 body 가 나간다 — 강사의 다른 일정 시각/위치명은 신청
 * 옵션({@code TIME_CONFLICT})에서 이미 드러나는 정보라 새 노출이 아니고, {@code sessionId} 로 닿는 일정
 * 엔드포인트는 전부 소유자 검사를 한다.
 */
@Getter
@Setter
public class SessionOverlapResult extends CommonResult {

    /** 새 일정과 시간이 겹친 기존 일정들(시작 시각 순). 비어 있지 않다. */
    private List<Conflict> conflicts;

    /** 겹친 일정 한 건 — 식별자 + 시각 + 위치(표시명은 해석 실패 시 null, 토큰은 보존). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Conflict {
        private Long sessionId;
        private LocalDate date;
        private LocalTime startTime;
        private LocalTime endTime;
        private String venueRefId;
        private String venueName;
    }
}
