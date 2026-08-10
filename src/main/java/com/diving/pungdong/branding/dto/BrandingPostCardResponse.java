package com.diving.pungdong.branding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

/**
 * 3-col 그리드 카드 1장. 목록은 이 최소 필드만 내려주고 본문·태그는 상세에서 받는다.
 *
 * <p>{@code mediaCount} 가 2 이상이면 FE 가 캐로셀 뱃지를 그린다. {@code hidden} 은 <b>오너 목록에만</b>
 * 실린다 — 공개 목록엔 숨긴 글이 아예 없으므로 의미가 없다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "posts")
public class BrandingPostCardResponse {

    private Long id;
    private String thumbnailUrl;
    private int mediaCount;

    @JsonProperty("pinned")
    private boolean pinned;

    /** 오너 목록 전용 — 공개 목록에선 키가 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean hidden;
}
