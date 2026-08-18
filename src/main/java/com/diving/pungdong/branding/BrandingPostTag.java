package com.diving.pungdong.branding;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 요청의 태그 문자열들을 <b>집계 가능한 형태로 교정</b>해 자식 행으로 만든다.
     * 커뮤니티·브랜딩 <b>두 쓰기 경로가 같은 걸 써야 한다</b> — 한쪽만 고치면 그쪽으로 들어온 태그가
     * 집계를 오염시키고, 그게 어느 쪽인지는 인기 태그 목록만 봐서는 알 수 없다.
     *
     * <p>하는 일은 셋뿐이다.
     * <ul>
     *   <li><b>앞뒤 공백과 선행 {@code #} 제거</b> — 계약상 저장값은 {@code #} 없는 순수 문자열인데
     *       (표시용 {@code #} 은 클라이언트가 붙인다) 그대로 두면 {@code #제주} 와 {@code 제주} 가
     *       서로 다른 태그로 갈려 인기 태그가 반씩 쪼개진다.</li>
     *   <li><b>빈 문자열 버리기</b> — {@code "#"} 하나만 보낸 경우.</li>
     *   <li><b>같은 글 안의 중복 제거</b>(대소문자 무시). 안 하면 한 글이 같은 태그를 두 번 담아
     *       카운트를 부풀릴 수 있다. 남기는 표기는 <b>첫 등장</b> 이다.</li>
     * </ul>
     *
     * <p><b>대소문자를 통일하지는 않는다.</b> {@code OW} 를 {@code ow} 로 저장하면 화면 표기가 망가지고,
     * 태그 대부분이 한글이라 실익도 없다 — 영문 대소문자가 갈리는 건 감수한다.
     *
     * <p>거부가 아니라 <b>교정</b>이라 DTO 검증({@code @Pattern})이 아니라 여기 있다. 사용자가 {@code #} 을
     * 붙여 보낸 걸 400 으로 되돌려주는 건 과하다. 길이·개수 상한은 그대로 DTO 가 본다.
     */
    public static List<BrandingPostTag> normalize(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String raw : rawTags) {
            if (raw == null) {
                continue;
            }
            String cleaned = raw.trim();
            while (cleaned.startsWith("#")) {
                cleaned = cleaned.substring(1).trim();
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            byKey.putIfAbsent(cleaned.toLowerCase(), cleaned);
        }
        List<BrandingPostTag> tags = new ArrayList<>();
        for (String tag : byKey.values()) {
            tags.add(BrandingPostTag.builder().tag(tag).build());
        }
        return tags;
    }
}
