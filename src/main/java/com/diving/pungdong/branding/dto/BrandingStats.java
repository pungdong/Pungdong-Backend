package com.diving.pungdong.branding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * 프로필 헤더 통계 — <b>전부 파생값</b>이다(컬럼에 비정규화하지 않는다). 카운터를 저장하면 정합성을
 * 맞출 일이 생기는데, 이 트래픽에선 조회마다 count 쿼리를 도는 편이 싸고 틀릴 일이 없다.
 *
 * <p>{@code posts} 는 게시물 도메인이 붙는 후속 PR 에서 채운다 — 그때까지 키를 생략한다(미구현은 생략,
 * 유저가 비운 값은 명시적 null 이라는 계약 규칙). FE 는 그동안 오너 게시물 목록의
 * {@code page.totalElements} 로 대체한다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandingStats {

    /** 게시물 수 — 후속 PR. */
    private Integer posts;

    /** 누적 수강생 수 — <b>강사만</b>. 확정(CONFIRMED)된 회차를 가진 distinct 학생 수. */
    private Integer students;
}
