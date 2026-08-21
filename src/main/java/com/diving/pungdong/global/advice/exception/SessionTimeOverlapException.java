package com.diving.pungdong.global.advice.exception;

import com.diving.pungdong.global.model.SessionOverlapResult;

import java.util.List;

/**
 * 강사의 일정(session)이 시간상 겹칠 때. 한 강사는 한 번에 한 세션만 운영하므로, 새 일정이 기존 일정과
 * (정확히 같은 위치·시간이 아니면서) 겹치면 거부한다. 맞닿는 경계(예: 08–11 + 11–14)는 겹침 아님.
 *
 * <p>무엇과 겹쳤는지를 {@link #getConflicts()} 로 실어 나른다 — advice 가 {@link SessionOverlapResult} 로
 * 내려 FE 가 "○○ 일정과 겹칩니다" 를 그릴 수 있게. 코드/문구만으로는 강사가 어느 일정을 비워야 하는지 알 수
 * 없었다.
 */
public class SessionTimeOverlapException extends RuntimeException {

    private final List<SessionOverlapResult.Conflict> conflicts;

    public SessionTimeOverlapException(List<SessionOverlapResult.Conflict> conflicts) {
        super();
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public List<SessionOverlapResult.Conflict> getConflicts() {
        return conflicts;
    }
}
