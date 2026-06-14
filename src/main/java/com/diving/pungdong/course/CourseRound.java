package com.diving.pungdong.course;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 회차 1건 — 정규(REGULAR) 또는 추가세션(EXTRA). 회차별 설명 + 진행 가능 위치(들). 1회차(첫 만남)는
 * 플랫폼 확정({@code platformConfirmed}). EXTRA 는 비용 정책(freeCount 회까지 무료, 이후 회당
 * perSessionPrice)을 추가로 가진다(chat19). 회차별 위치+장비는 코스 작성에서 회차 간 복사 가능(UI),
 * 저장은 회차마다 독립.
 */
@Entity
@Table(name = "course_round")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CourseRound {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @Enumerated(EnumType.STRING)
    private RoundKind roundKind;

    /** REGULAR: 1..N. EXTRA: null. ('index' 는 예약어라 컬럼명 round_index.) */
    @Column(name = "round_index")
    private Integer roundIndex;

    private boolean platformConfirmed;

    @Lob
    private String description;

    /** EXTRA 전용 — N회까지 무료. */
    private Integer freeCount;
    /** EXTRA 전용 — 무료 소진 후 회당 가격(원). */
    private Integer perSessionPrice;

    @OneToMany(mappedBy = "round", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<RoundVenue> venues = new ArrayList<>();

    public void addVenue(RoundVenue v) {
        v.setRound(this);
        this.venues.add(v);
    }
}
