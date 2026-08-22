package com.diving.pungdong.course.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 강의 저장/해제 응답 — <b>갱신된 카운트와 내 상태를 함께</b> 돌려준다.
 *
 * <p>클라이언트가 낙관적 업데이트(먼저 UI 를 바꾸고 나중에 서버 확인)를 해도 이 값으로 덮어쓰면 항상
 * 수렴한다. 카운트를 안 주면 FE 가 로컬에서 ±1 을 하는데, 그 사이 남이 누른 변화가 반영되지 않아
 * 화면 숫자가 서서히 어긋난다.
 *
 * <p><b>와이어 모양은 커뮤니티의 {@code ReactionResponse} 와 똑같다</b>({@code {count, active}}) —
 * TS 클라이언트는 그 타입을 그대로 재사용하면 된다. 그런데도 그 클래스를 <b>import 하지 않는다</b>:
 * {@code community} 는 이미 {@code course} 를 참조하고(글에 강의 링크), 여기서 거꾸로 참조하면 두
 * 도메인이 서로를 물어 의존 방향이 한쪽으로 흐르지 않는다(레포 규약). 필드 2개짜리 와이어 타입을
 * 한 번 더 두는 값이 그 순환보다 싸다. 세 번째 도메인에 같은 토글이 생기면 그때 {@code global} 로
 * 올린다(그게 {@code PageClamp} 가 올라간 방식이다).
 */
@Getter
@Builder
public class CourseBookmarkResponse {

    private final long count;

    /** 이 요청을 보낸 사람의 현재 상태. POST 뒤엔 true, DELETE 뒤엔 false. */
    private final boolean active;
}
