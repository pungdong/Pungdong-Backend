package com.diving.pungdong.branding;

import lombok.*;

import javax.persistence.*;

/** 게시물 태그 1건. 디자인 문구상 "검색/추천에 사용" 되므로 별도 행으로 둔다(JSON 컬럼이면 색인이 안 된다). */
@Entity
@Table(name = "branding_post_tag")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BrandingPostTag {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private BrandingPost post;

    @Column(nullable = false, length = 30)
    private String tag;
}
