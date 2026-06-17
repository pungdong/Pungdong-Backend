package com.diving.pungdong.global.advice.exception;

/**
 * 강사의 일정(session)이 시간상 겹칠 때. 한 강사는 한 번에 한 세션만 운영하므로, 새 일정이 기존 일정과
 * (정확히 같은 위치·시간이 아니면서) 겹치면 거부한다. 맞닿는 경계(예: 08–11 + 11–14)는 겹침 아님.
 */
public class SessionTimeOverlapException extends RuntimeException {
    public SessionTimeOverlapException() {
        super();
    }
}
