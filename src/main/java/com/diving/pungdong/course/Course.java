package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.venue.Region;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 강사 코스(강의) — V2 코스 작성 화면의 본체(legacy {@code Lecture} 의 후신, 공존). 기본정보 + 회차 +
 * (선택)추가세션. 위치는 {@code RoundVenue.venueRefId} 로 참조하고, 위치별 대여 장비는 강사×위치 가격표
 * ({@code venue.equipment})에서 읽기 시점에 합성 — 코스가 장비를 복제하지 않는다.
 *
 * <p>자식 컬렉션은 전량 교체 스냅샷(cascade ALL + orphanRemoval + {@code clearChildren()}/{@code addX()}),
 * Account 단방향 참조(venue/instructor-application 스타일).
 */
@Entity
@Table(name = "course")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Course {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id")
    private Account instructor;

    private String title;

    @Enumerated(EnumType.STRING)
    private CourseKind kind;

    /** 자격증 발급 단체 코드(Sanity certOrg.code). CERTIFICATION 만 필수. */
    private String organizationCode;

    /** 종목 코드(discipline.code) — DisciplineService 검증. */
    private String disciplineCode;

    /** 목표 평탄화 레벨 — CERTIFICATION 만(>=1, >=2 ⇒ 패키지). 비-자격은 비움. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "course_level", joinColumns = @JoinColumn(name = "course_id"))
    @Column(name = "cert_level")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<CertLevel> levels = new LinkedHashSet<>();

    /** 정규 회차 수(REGULAR 회차 개수와 일치해야 함). */
    private int totalRounds;

    /** 수강료(원, 부가세 포함 최종가). */
    private int price;

    @Lob
    private String description;

    @Enumerated(EnumType.STRING)
    private CourseStatus status;

    /**
     * 둘러보기 지역 필터용 비정규화 facet — 회차 위치들이 속한 지역 묶음 집합. 저장 시점에 위치 주소에서
     * 파생({@link com.diving.pungdong.venue.VenueRefResolver}). OFFICIAL 위치 주소는 Sanity 캐시라
     * 쿼리 타임 JOIN 불가 → 스냅샷이 단일 해법. (위치 이사 시 코스 재저장 전까지 stale — 드물어 MVP 허용.)
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "course_region", joinColumns = @JoinColumn(name = "course_id"))
    @Column(name = "region")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<Region> regions = new LinkedHashSet<>();

    /** 카드 표시용 대표 위치 이름(첫 회차 첫 위치) — 읽기 시 N+1 위치 해석 회피용 비정규화. */
    private String primaryLocationName;

    /**
     * 데모(시드) 코스 여부 — 정식 강사가 만든 코스와 구분하는 표식. 정식 작성 경로(CourseController)에선
     * 절대 true 가 되지 않고, 데모 시더만 직접 표시한다. FE 는 "샘플용" 태그로 구분 노출하고, 둘러보기는
     * {@code siteSettings.showSeededCourses=false} 일 때 이 값으로 제외한다. 런칭 시 데이터를 지우지 않고도
     * 가릴 수 있게 하는 단일 표식(데이터 ↔ 노출 분리). 기본 false.
     */
    private boolean seeded;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<CourseMedia> media = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("roundKind asc, roundIndex asc, id asc")
    @Builder.Default
    private List<CourseRound> rounds = new ArrayList<>();

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 레벨 2개 이상 = 한 상품으로 묶인 패키지(별도 토글 없음, chat45). */
    public boolean isPackage() {
        return levels != null && levels.size() >= 2;
    }

    public void addMedia(CourseMedia m) {
        m.setCourse(this);
        this.media.add(m);
    }

    public void addRound(CourseRound r) {
        r.setCourse(this);
        this.rounds.add(r);
    }

    /** 수정(전량 교체 스냅샷) 전 자식 비우기 — orphanRemoval 로 DB 에서도 제거. */
    public void clearChildren() {
        this.media.clear();
        this.rounds.clear();
    }
}
