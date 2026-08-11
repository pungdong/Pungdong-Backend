package com.diving.pungdong.community;

/**
 * 피드의 작성자 유형 필터 — 웹 피드의 "강사 글" pill.
 *
 * <p>값이 하나뿐인 건 <b>필터가 하나뿐이기 때문</b>이다. 생략 = 전체이고, "일반 유저 글만" 은 화면 어디에도
 * 없어서 만들지 않았다 — 죽은 enum 값을 남기지 않는다는 이 도메인의 규칙(정렬 {@code SOONEST} 을 뺀 것과
 * 같은 판단).
 *
 * <p><b>강사 판정은 승인된 강사 신청</b>({@code instructor_application.status = APPROVED})이다 —
 * 작성자 칩의 {@code isInstructor} 와 <b>같은 축</b>이라, 칩이 붙은 글만 정확히 걸러진다. 코스 소유
 * 여부로 판정하면 칩과 필터 결과가 어긋난다.
 */
public enum AuthorType {
    INSTRUCTOR
}
