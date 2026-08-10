package com.diving.pungdong.branding;

import com.diving.pungdong.course.Course;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 브랜딩 페이지 게시물 — 3-col 그리드 한 칸이자 상세 화면 한 장.
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

    @Column(length = 2000)
    private String caption;

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
