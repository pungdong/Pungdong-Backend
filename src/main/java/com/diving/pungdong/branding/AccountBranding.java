package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 브랜딩 페이지(강사) / 내 프로필(일반) — 계정당 1개. 강사 전용이 아니라 <b>모든 계정</b>이 가질 수 있다
 * (사용자 결정 D2: 커뮤니티 활동·버디매칭 시 자기어필). 그래서 이름이 {@code instructor_}가 아니라
 * {@code account_branding} 이다.
 *
 * <p><b>생성 시점</b>: 별도 생성 엔드포인트가 없다. 첫 쓰기(프로필 편집 / 첫 게시물 작성)가 곧 생성이다
 * — 조회(GET)는 절대 생성하지 않는다. 자세한 근거는 contract §4.5.
 *
 * <p>강사 한정 요소(자격 뱃지·강의 연결·수강생 수·검수 배너)는 <b>이 엔티티가 아니라 읽기 시점에 합성</b>
 * 한다 — branding 은 account·instructorapplication·course 를 단방향으로 참조만 한다.
 */
@Entity
@Table(name = "account_branding")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AccountBranding {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유 계정 — 1:1. 공개 조회는 {@code account.nickName} 으로 들어온다(D3, handle 폐기). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", unique = true, nullable = false)
    private Account account;

    /** 한 줄 소개. 유저가 비우면 null(= "미구현"이 아니라 "지웠다"를 구분하기 위해 명시적 null). */
    @Column(length = 60)
    private String tagline;

    @Column(length = 500)
    private String bio;

    /** 활동 지역 자유 입력(예 "서울 · 부산"). {@code Course.regions} 파생은 신규/일반 유저가 빈 값이 되어 쓰지 않는다. */
    @Column(name = "location_label", length = 60)
    private String locationLabel;

    /** 공개 여부. 첫 쓰기로 생성될 때 true — 그 시점엔 이미 내용이 하나는 들어 있어 빈 페이지가 공개될 일이 없다. */
    @Column(name = "is_published", nullable = false)
    private boolean isPublished;

    @Builder.Default
    @OneToMany(mappedBy = "branding", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<BrandingRecord> records = new ArrayList<>();

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

    /** 기록 스냅샷 교체용 — course/venue 와 동일 관례(전체 교체가 재정렬까지 원자적으로 처리). */
    public void replaceRecords(List<BrandingRecord> next) {
        this.records.clear();
        next.forEach(record -> {
            record.setBranding(this);
            this.records.add(record);
        });
    }
}
