package com.diving.pungdong.community.dto;

import com.diving.pungdong.branding.CommunityCategory;
import lombok.Builder;
import lombok.Getter;
import org.springframework.hateoas.server.core.Relation;

/**
 * 카테고리별 이번 주 글 수 — 피드 상단 4-up 그리드. 클라이언트가 {@code > 50} 이면 HOT 뱃지를 붙인다
 * (임계값은 클라이언트 상수, 숫자만 서버 데이터).
 *
 * <p>카테고리가 없는 글(브랜딩발)은 어느 칸에도 속하지 않아 집계에서 빠진다. 4종 전부 항상 내려간다 —
 * 글이 0개인 카테고리도 칸은 그려져야 하므로 <b>행이 없으면 0 으로 채워서</b> 준다.
 */
@Getter
@Builder
@Relation(collectionRelation = "categories")
public class CategoryCountResponse {

    private final CommunityCategory category;

    /** 최근 7일 글 수. */
    private final long weeklyPostCount;
}
