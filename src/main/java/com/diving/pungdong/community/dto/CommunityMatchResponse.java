package com.diving.pungdong.community.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 같이가요 모집 정보. {@code MATCH} 카테고리 글에만 실린다.
 *
 * <p><b>일정은 civil time 이다</b> — {@code meetDate}/{@code meetTime} 에 오프셋이 없다. 다이브 포인트의
 * 벽시계 시각이라 뷰어 타임존으로 변환하면 안 된다({@code createdAt} 같은 절대시각과 다른 축).
 *
 * <p><b>참여자 수가 없다.</b> "참여 신청" 을 별도 기능으로 만들지 않기로 확정돼(신청류는 기존 수강신청
 * 플로우로 간다) 정원만 있고 신청자 개념이 없다. 클라이언트는 모집 칸을 "N명 모집" 으로 렌더한다.
 */
@Getter
@Builder
public class CommunityMatchResponse {

    /** "2026-05-24" — 오프셋 없음. */
    private final LocalDate meetDate;

    /** "09:00:00" 입수 시각. 날짜만 정한 모집이면 null. */
    private final LocalTime meetTime;

    /** 모집 정원. */
    private final int capacity;

    /** 요구 자격 자유 텍스트 — "AOWD 이상 · 보트다이빙 경험". */
    private final String levelLabel;

    /**
     * 모집이 열려 있나 — 파생값({@code meetDate >= today}).
     *
     * <p><b>뱃지용이 아니다.</b> "모집중/마감" 뱃지가 있던 화면은 Phase 1 범위 밖이라 렌더되지 않는다.
     * 클라이언트는 이 값으로 <b>지난 모집글을 흐리게</b> 처리한다 — 지난 글이 멀쩡해 보이면 안 되기 때문.
     */
    private final boolean open;
}
