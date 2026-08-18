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
 * <p><b>모든 글은 커뮤니티 글이다({@code showInFeed=true}).</b> 갈리는 건 "작성자의 프로필 그리드에도
 * 남길지"({@code showOnProfile}) 하나뿐이고, 그건 <b>작성자가 작성·수정에서 고른다</b>
 * ({@code CommunityPostRequest.showOnProfile}, 기본 {@code false}).
 * <ul>
 *   <li>{@code showOnProfile=false} → 커뮤니티 피드에만</li>
 *   <li>{@code showOnProfile=true}  → 피드 + 프로필 그리드(= 예전 브랜딩 글)</li>
 * </ul>
 * 2026-08-18 작성 폼 통합 전에는 <b>작성 경로</b>가 이 값을 정했다(브랜딩 경로=true / 커뮤니티 경로=false).
 * 지금은 경로가 아니라 요청이 정한다 — 구 {@code POST /branding/me/posts} 는 구버전 앱 호환으로만 남아
 * 있고 거기서 온 글은 여전히 {@code true} 다. DB 기본값은 기존 행 backfill 용이지 신규 쓰기용이 아니다.
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
     * 커뮤니티 카테고리. <b>NOT NULL</b>(V31) — 모든 글은 커뮤니티 글이므로 분류축이 반드시 있다.
     *
     * <p>V19~V30 동안은 nullable 이었다: 브랜딩 작성 경로가 카테고리를 안 받던 시절의 글이 있었고,
     * FE 배포 창(window) 동안 null 이 <b>정당한 값</b>이었기 때문이다. 작성 폼이 하나로 합쳐지면서
     * (2026-08-18) 두 경로 모두 카테고리를 요구하게 됐고, 기존 행은 V31 에서 backfill 했다.
     * 카테고리 없는 글이 남아 있으면 "오타 하나 고치려는 강사가 없던 카테고리를 발명해야" 수정이 된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private CommunityCategory category;

    /**
     * 글 제목. <b>NOT NULL</b>(V31) — 카테고리와 같은 이유로 두 쓰기 경로 모두 필수로 받는다.
     *
     * <p>V19~V30 동안은 nullable 이었다: 브랜딩 게시물은 caption 이 곧 본문이라 제목이 없었다.
     * 작성 폼이 하나로 합쳐지면서(2026-08-18) 그 전제가 사라졌고, 기존 행은 V31 에서 backfill 했다.
     */
    @Column(length = 100, nullable = false)
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

    /**
     * 어드민이 신고를 조치한 시각. {@code null} 이면 조치된 적 없다.
     *
     * <p><b>{@code isHidden} 과 따로 있는 이유</b>: 숨김의 주인이 둘(작성자·어드민)인데 상태 컬럼이
     * 하나뿐이면, 신고로 내려간 글을 작성자가 토글 한 번으로 되살려 조치가 무효가 된다.
     * 이 컬럼이 "누가 내렸는지" 를 기억한다 — 값이 있으면 작성자는 다시 공개할 수 없다.
     *
     * <p><b>왜 신고 테이블을 읽지 않고 컬럼을 두나</b>: 신고는 {@code moderation} 패키지 소유인데
     * 이 판정은 커뮤니티(작성자의 공개 전환)가 한다. 신고를 읽으면
     * {@code community → moderation → community} 순환이 된다. 조치 사실을 대상 도메인에 남기면
     * 각자 <b>자기 컬럼만</b> 보면 되고 의존이 한 방향으로 정리된다.
     */
    @Column(name = "moderated_at")
    private OffsetDateTime moderatedAt;

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
