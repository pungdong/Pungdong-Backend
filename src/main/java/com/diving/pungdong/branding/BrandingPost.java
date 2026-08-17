package com.diving.pungdong.branding;

import com.diving.pungdong.course.Course;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 게시물 — <b>브랜딩 그리드와 커뮤니티 피드가 공유하는 하나의 엔티티</b>다.
 *
 * <p><b>왜 테이블이 하나인가.</b> {@code account_branding} 은 계정당 1행(UNIQUE)이고 글의 작성자는 1명이라
 * "글 ↔ 브랜딩 페이지" 관계는 항상 0..1 이다. 맵핑 테이블을 두면 모든 글에 정확히 0행 또는 1행이 생기는데
 * 그건 조인을 한 번 더 타는 boolean 이다. 두 테이블로 나누면 브랜딩 그리드가 UNION 이 되어 정렬·페이징이
 * 얹히고 좋아요·댓글·신고가 폴리모픽 FK 로 빠진다. 그래서 노출 플래그 두 개로 가른다.
 *
 * <p><b>노출은 브랜딩 → 커뮤니티 단방향이다.</b> 브랜딩은 하이라이트(남기고 싶은 것만), 커뮤니티는
 * 흐름(오늘의 이야기)이다. 하이라이트는 흐름에 실릴 가치가 있지만 흐름의 모든 글이 하이라이트일 이유는 없다.
 * <ul>
 *   <li>브랜딩에서 작성 → {@code showOnProfile=true}, {@code showInFeed=true}</li>
 *   <li>커뮤니티에서 작성 → {@code showOnProfile=false}, {@code showInFeed=true}</li>
 * </ul>
 * 두 플래그는 <b>작성 경로가 명시 설정</b>한다. DB 기본값은 기존 행 backfill 용이지 신규 쓰기용이 아니다.
 *
 * <p><b>클래스명이 {@code BrandingPost} 로 남아 있는 이유.</b> 물리 테이블명을 바꾸지 않았기 때문이다 —
 * ECS 롤링 배포 중 구버전 태스크가 살아 있는 동안 RENAME 이 돌면 그 태스크가 없는 테이블을 조회해
 * 브랜딩 페이지가 500 이 된다. 클래스명은 테이블명과 맞춰 두고, 이름 정리가 필요하면 트래픽 없는 시점에
 * 테이블·클래스를 함께 바꾸는 별도 PR 로 간다.
 *
 * <p><b>숨김({@code isHidden})은 삭제와 다르다.</b> 오너 액션시트의 "숨기기 · 프로필에 표시되지 않아요"는
 * <b>되돌릴 수 있는</b> 상태다. 공개 목록·상세에서만 빠지고 오너 목록에는 남는다 — 그래야 다시 켤 수 있다.
 *
 * <p>{@code linkedCourse} 는 <b>nullable</b> 이고 DB 에서 {@code ON DELETE SET NULL} 이다. 코스가 지워져도
 * 게시물은 살고 연결만 끊긴다.
 */
@Entity
@Table(name = "branding_post")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BrandingPost {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branding_id", nullable = false)
    private AccountBranding branding;

    /**
     * 커뮤니티 카테고리. <b>nullable</b> — 브랜딩에서 작성한 글은 카테고리 개념이 없다.
     * null 이면 카테고리 필터에 안 잡히고 "전체" 피드에만 노출된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CommunityCategory category;

    /**
     * 커뮤니티 글의 제목. <b>nullable</b> — 브랜딩 게시물은 caption 이 곧 본문이라 제목이 없다.
     * 커뮤니티 작성 경로에서는 필수(앱 레벨 검증).
     */
    @Column(length = 100)
    private String title;

    /**
     * 본문. <b>두 작성 경로가 서로 다른 상한을 갖는다</b> — 브랜딩 2000자, 커뮤니티 5000자.
     * 컬럼은 넓은 쪽(5000)을 수용하고, 실제 상한은 각 요청 DTO 가 건다(V30).
     */
    @Column(length = 5000)
    private String caption;

    /** 커뮤니티 피드 노출 여부. 작성 경로가 명시 설정한다(클래스 Javadoc 참고). */
    @Column(name = "show_in_feed", nullable = false)
    private boolean showInFeed;

    /** 브랜딩 그리드 노출 여부. 작성 경로가 명시 설정한다. */
    @Column(name = "show_on_profile", nullable = false)
    private boolean showOnProfile;

    @Column(name = "location_label", length = 60)
    private String locationLabel;

    /** 그리드 상단 고정. 정렬은 pinned → 최신순. */
    @Column(nullable = false)
    private boolean pinned;

    /** 숨김(되돌릴 수 있음). 공개 경로에서만 제외된다. */
    @Column(name = "is_hidden", nullable = false)
    private boolean isHidden;

    /** 연결된 강의 — 강사만 설정할 수 있고, 없으면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_course_id")
    private Course linkedCourse;

    @Builder.Default
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<BrandingPostMedia> media = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BrandingPostTag> tags = new ArrayList<>();

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 미디어·태그 스냅샷 교체 — 수정은 부분 갱신이 아니라 통째 교체다(course 관례). */
    public void replaceMedia(List<BrandingPostMedia> next) {
        this.media.clear();
        next.forEach(m -> {
            m.setPost(this);
            this.media.add(m);
        });
    }

    public void replaceTags(List<BrandingPostTag> next) {
        this.tags.clear();
        next.forEach(t -> {
            t.setPost(this);
            this.tags.add(t);
        });
    }

    /** 그리드 썸네일 — 대표는 항상 sortOrder 0번(= 배열 첫 장). */
    public String thumbnailUrl() {
        return media.isEmpty() ? null : media.get(0).getUrl();
    }
}
