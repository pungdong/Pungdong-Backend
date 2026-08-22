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
 * <p>자식 컬렉션은 cascade ALL + orphanRemoval 이고 Account 는 단방향 참조(venue/instructor-application
 * 스타일). 단 <b>수정 시 미디어와 회차가 다르게 움직인다</b> — 미디어는 전량 교체({@link #clearMedia()}),
 * <b>회차는 행을 재사용</b>한다({@code CourseService.reconcileRounds}). {@code enrollment_round} 가 회차를
 * FK 로 참조해서, 지웠다 다시 만들면 수강생 있는 강의가 참조 무결성 위반으로 터지기 때문이다(#318).
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

    /**
     * 어드민이 신고를 조치해 차단한 시각. {@code null} 이면 정상.
     *
     * <p><b>{@link CourseStatus} 로 표현하지 않은 이유</b>: DRAFT/OPEN/CLOSED 는 <b>강사가 스스로 바꾸는
     * 영업 상태</b>다({@code PATCH /courses/{id}/status} 로 자유롭게 오간다). 조치를 CLOSED 로 내리면
     * 강사가 즉시 되돌린다 — 어드민 조치는 강사가 만질 수 없는 별도 축이어야 한다.
     * {@code seeded} 와 같은 모양의 "쓰기 경로가 없는 플래그" 다.
     *
     * <p><b>효과는 노출과 신규 신청까지다.</b> 둘러보기·공개 상세·강의 수 집계·게시물의 연결 강의 카드에서
     * 빠지고, 새 수강신청·다음 회차 잡기가 막힌다. <b>이미 확정·결제된 수강은 건드리지 않는다</b> —
     * 레포의 "확정 취소 없음" 원칙이고, 돈이 오간 관계를 조치가 일방적으로 끊으면 환불·분쟁이 된다.
     *
     * <p>⚠️ 이 플래그를 <b>연관관계를 끊는 방식</b>(enrollment 의 course 를 null 로)으로 구현하지 말 것.
     * 수강 일정 카드·환불 계산·채팅방 제목이 전부 {@code enrollment.getCourse()} 를 타고 있어서
     * 조용히 무너진다(환불 비율까지 바뀐다). 필터는 <b>조회 쿼리에만</b> 더한다.
     */
    @Column(name = "blocked_at")
    private java.time.OffsetDateTime blockedAt;

    /** 어드민 조치로 가려진 강의인가. 노출·신규 신청 판정의 단일 표현. */
    public boolean isBlocked() {
        return blockedAt != null;
    }

    /**
     * <b>최초로 OPEN 된 시각.</b> 한 번 세우면 되돌리지 않는다 — DRAFT/CLOSED 로 내려도 남는다.
     *
     * <p><b>왜 {@code status == CLOSED} 만으로 부족한가.</b> {@link CourseStatus} 전이는 자유라
     * <b>DRAFT → CLOSED 직행</b>이 가능하다. 그건 "마감된 강의" 가 아니라 <b>한 번도 발행된 적 없는
     * 초안</b>이고, 색인 자산이 애초에 없다. 그런 걸 공개 상세로 열면 강사가 공개를 선택한 적 없는
     * 내용(가제목·미완성 사진)이 노출된다 — 레포의 "존재 숨김" 규약 위반이다. 그래서 읽기 게이트는
     * 상태가 아니라 <b>발행 이력</b>을 본다({@code CourseService.requirePubliclyReadable}).
     *
     * <p>⚠️ <b>{@code datePublished} 로 내보내지 말 것.</b> V37 백필은 마이그레이션 이전 행에
     * {@code COALESCE(updated_at, created_at)} 를 넣었는데 CLOSED 행에선 그게 사실상 <b>마감</b>
     * 시각이다. 지금 이 값의 용도는 불리언({@link #isEverPublished}) 하나뿐이다.
     */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 한 번이라도 공개된 적이 있는가 — 읽기(색인) 게이트의 단일 판정. */
    public boolean isEverPublished() {
        return publishedAt != null;
    }

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

    /**
     * 수정 전 미디어 비우기 — orphanRemoval 로 DB 에서도 제거.
     *
     * <p><b>회차와 달리 전량 교체해도 안전하다</b> — {@code course_media} 를 FK 로 참조하는 테이블이 없다.
     * 회차({@code course_round})는 {@code enrollment_round} 가 참조하므로 지우면 안 되고, 그래서
     * {@code CourseService.reconcileRounds} 가 행을 <b>재사용</b>한다.
     */
    public void clearMedia() {
        this.media.clear();
    }

    /**
     * 사라진 회차만 제거 — orphanRemoval 로 DB 에서도 삭제된다. <b>수강 기록이 참조하지 않는 회차만</b>
     * 넘겨야 한다(호출부가 {@code CourseRoundUsageProbe} 로 먼저 확인한다).
     *
     * <p>id 로 비교하지 않고 <b>동일성(identity)</b>으로 지운다 — {@code @EqualsAndHashCode(of = "id")} 라서
     * 아직 저장 안 된 회차끼리는 id 가 모두 null 이라 서로 "같다"고 판정된다(equals 기반으로 지우면 엉뚱한
     * 회차가 딸려 나간다).
     */
    public void removeRounds(java.util.Collection<CourseRound> gone) {
        this.rounds.removeIf(existing -> gone.stream().anyMatch(g -> g == existing));
    }

    /**
     * 회차 정렬을 DB 적재 순서({@code @OrderBy})와 <b>같게</b> 맞춘다 — 재사용·추가로 리스트 끝에 붙은
     * 회차 때문에 응답 순서가 뒤바뀌면, 방금 받은 응답과 다시 조회한 결과가 달라진다.
     */
    public void sortRounds() {
        this.rounds.sort(java.util.Comparator
                .comparing((CourseRound r) -> r.getRoundKind() == null ? "" : r.getRoundKind().name())
                .thenComparing(CourseRound::getRoundIndex,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(CourseRound::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
    }
}
