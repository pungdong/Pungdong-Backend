package com.diving.pungdong.global.advice.exception;

/**
 * 코스 수정이 <b>수강생이 물려 있는 회차를 없애려 할 때</b> — 회차 수를 줄이거나 추가세션을 뺐는데, 사라지는
 * 회차를 이미 누군가 신청했다.
 *
 * <p>BE 는 그 수강 기록을 임의로 정리하지 않는다(남의 예약·결제다). 식별 가능한 코드를 내려 FE 가
 * "그 회차에 수강생이 있어 줄일 수 없다"로 안내하게 한다 — {@code CoverageHasSessionException}(-1014) 과 같은 결.
 *
 * <p>이 예외가 없던 시절엔 같은 상황이 참조 무결성 위반으로 <b>500</b> 이 났다(공통 에러 봉투도 아니라 FE
 * 에러 핸들링에 걸리지 않았다). 그래서 이건 "새 제약"이 아니라 <b>이미 불가능하던 것에 이름을 붙인 것</b>이다.
 */
public class CourseRoundInUseException extends RuntimeException {
    public CourseRoundInUseException() {
        super();
    }
}
