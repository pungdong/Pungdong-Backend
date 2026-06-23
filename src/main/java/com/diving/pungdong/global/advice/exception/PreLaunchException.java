package com.diving.pungdong.global.advice.exception;

/**
 * 정식 런칭 전(=Sanity {@code siteSettings.launched=false})에 수강신청을 시도할 때. 모든 코스(실강사 것
 * 포함)에 적용되는 전역 게이트 — FE 의 "정식 런칭을 기다려주세요" 배너와 이중으로, 서버에서도 신청을
 * 거부한다(FE 만 믿지 않게). 식별 가능한 코드를 내려 FE 가 런칭대기 안내로 분기하게 한다.
 */
public class PreLaunchException extends RuntimeException {
    public PreLaunchException() {
        super();
    }
}
