package com.diving.pungdong.branding.dto;

import com.diving.pungdong.course.CourseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * 게시물에 연결된 강의 카드 — 상세 화면의 climax.
 *
 * <p>상태 규칙: {@code OPEN} 은 정상 카드, {@code CLOSED} 는 FE 가 마감 분기(그레이스케일·취소선·
 * "다른 강의 보기" CTA)를 그린다. <b>{@code DRAFT}(미공개)이거나 코스가 삭제된 경우엔 이 객체 자체를
 * 안 내려준다</b> — 공개 페이지에 미공개 코스가 새면 안 되고, 게시물은 그대로 살아야 한다.
 *
 * <p>디자인의 부제("4박5일 · 입문 OW 이상 · 8명")는 <b>BE 가 만들지 않는다</b> — 기간·정원이 {@code Course}
 * 에 없어서다({@code totalRounds} 는 회차 수로 다른 개념). FE 가 있는 재료로 조립한다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class LinkedCourseResponse {

    private Long id;
    private String title;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String thumbnailUrl;

    private int price;
    private CourseStatus status;
}
