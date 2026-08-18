package com.diving.pungdong.branding.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 추천 강사 묶음 — 카드 목록 + 전체 수.
 *
 * <p><b>{@code totalCount} 를 같이 싣는 이유</b>: 홈의 공식 강사 카드가 "검수 마친 공식 강사 <b>N명</b>"
 * 이라는 숫자와 아바타 몇 장을 함께 그린다. 목록만 주면 그 숫자를 위해 다른 API 를 한 번 더 불러야 한다.
 *
 * <p>⚠️ <b>{@code totalCount} 는 {@code instructors} 와 같은 모집단을 센다</b> — 승인됐고
 * <b>프로필을 발행한</b> 강사. 승인만 되고 프로필이 비어 있는 강사는 양쪽 모두에서 빠진다.
 * 숫자와 아바타가 다른 집합을 가리키면 "12명이라면서 왜 열리는 건 5명뿐인가" 가 된다.
 * ("승인된 강사 전체" 의 수가 필요하면 그건 기존 {@code GET /instructors/public} 의
 * {@code page.totalElements} 다 — 세는 대상이 다른 별개의 숫자다.)
 */
@Getter
@Builder
public class SuggestedInstructorsResponse {

    /** 추천 가능한(승인 + 프로필 발행) 강사 총 수. `instructors` 길이와 다르다 — 그쪽은 limit 만큼만. */
    private final long totalCount;

    /** 무작위로 고른 카드들. 매 요청 순서·구성이 바뀐다. */
    private final List<SuggestedInstructorResponse> instructors;
}
