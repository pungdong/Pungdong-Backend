package com.diving.pungdong.community.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 좋아요·북마크 토글 응답 — <b>갱신된 카운트와 내 상태를 함께</b> 돌려준다.
 *
 * <p>클라이언트가 낙관적 업데이트(먼저 UI 를 바꾸고 나중에 서버 확인)를 해도 이 값으로 덮어쓰면 항상
 * 수렴한다. 카운트를 안 주면 FE 가 로컬에서 ±1 을 하는데, 그 사이 남이 누른 변화가 반영되지 않아
 * 화면 숫자가 서서히 어긋난다.
 *
 * <p>토글이 <b>멱등</b>이라 같은 요청을 두 번 보내도 이 응답이 같다 — 마커 테이블의
 * {@code (대상, 계정)} UNIQUE 가 그걸 보장한다.
 */
@Getter
@Builder
public class ReactionResponse {

    private final long count;

    /** 이 요청을 보낸 사람의 현재 상태. POST 뒤엔 true, DELETE 뒤엔 false. */
    private final boolean active;
}
