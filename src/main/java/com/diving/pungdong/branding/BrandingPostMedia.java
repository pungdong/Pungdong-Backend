package com.diving.pungdong.branding;

import lombok.*;

import javax.persistence.*;

/**
 * 게시물 미디어 1건 — 캐로셀 한 칸. {@code sortOrder} 0번이 그리드 썸네일(대표)이다.
 *
 * <p>{@code url} 은 {@code POST /branding-images} 로 먼저 올려 받은 <b>완성 CDN URL</b>(2-phase 업로드).
 * 저장 시 우리 CDN base 로 시작하는지 검증하므로 임의 외부 URL 을 넣을 수 없다.
 */
@Entity
@Table(name = "branding_post_media")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BrandingPostMedia {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BrandingPost post;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BrandingMediaKind kind;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
