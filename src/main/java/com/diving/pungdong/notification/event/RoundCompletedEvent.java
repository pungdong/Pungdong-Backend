package com.diving.pungdong.notification.event;

import lombok.Builder;
import lombok.Value;

/**
 * 회차 수업이 완료 → <b>학생</b>에게. 리뷰 유도 훅.
 *
 * <p>⚠️ 완료 경로가 <b>둘</b>이다 — 강사 수동({@code completeRound}/{@code completeSession})과
 * 세션일+24h 자동 sweep({@code markDone}). 양쪽 모두에서 발행해야 한다. 중복 발행 위험은 없다:
 * 두 경로 다 {@code doneAt != null} 이면 아무 것도 하지 않고 빠져나간다(멱등).
 */
@Value
@Builder
public class RoundCompletedEvent {
    Long studentAccountId;
    Long courseId;
    Long enrollmentId;
    Long roundId;
    String courseTitle;
}
