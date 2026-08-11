package com.diving.pungdong.community.dto;

import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

/** 인기 태그 — 웹 좌측 sidebar. 건수 내림차순, 동률이면 태그 사전순(순서가 매 요청 흔들리지 않게). */
@Getter
@Builder
@Relation(collectionRelation = "tags")
public class PopularTagResponse {

    /** {@code #} 없이 순수 태그 문자열. 표시용 {@code #} 는 클라이언트가 붙인다. */
    private final String tag;

    private final long count;
}
