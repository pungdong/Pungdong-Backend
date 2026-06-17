package com.diving.pungdong.global.advice.exception;

/**
 * 예약가능시간(coverage) 축소/삭제가 그 구간에 걸친 일정(session)을 가로지를 때. BE 는 자동으로 일정을
 * 정리하지 않는다(CS 유발) — 식별 가능한 코드를 내려 FE 가 "내부 일정을 먼저 정리해주세요" 로 유도하게 한다.
 */
public class CoverageHasSessionException extends RuntimeException {
    public CoverageHasSessionException() {
        super();
    }
}
