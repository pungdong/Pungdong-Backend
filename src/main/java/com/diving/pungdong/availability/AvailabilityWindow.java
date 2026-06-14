package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 가용시간 window — V2 디자인 2층 모델의 <b>1층(이론적 가능성)</b>. 강사가 "이 날 이 시간에 열려 있다"고
 * 선언한 한 칸(캘린더의 한 블록). 2층(실제 점유)은 {@link AvailabilityHold}(외부/수동) + 미래 enrollment.
 *
 * <p>{@code venueRefId}/{@code sessionLabel} 은 nullable — 빈 가용시간(available)은 위치 없이 시간만.
 * 특정 위치·세션으로 지정되면(외부예약·코스 배정) 채워진다. 위치 토큰은 venue 도메인과 동일
 * ("CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;"). 표시 이름은 읽기 시점에 {@code VenueRefResolver} 로 해석.
 *
 * <p>{@code Account} 단방향 참조(venue/course 스타일), 자식 hold 는 전량 교체 스냅샷
 * (cascade ALL + orphanRemoval + {@link #clearHolds()}/{@link #addHold}).
 */
@Entity
@Table(name = "availability_window")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AvailabilityWindow {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id")
    private Account instructor;

    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    /** 정원(폼의 maxCapacity). 외부 hold 가 넘치면 자동 확장될 수 있다({@code AvailabilityService}). */
    private int capacity;

    /** "CUSTOM:&lt;pk&gt;" | "OFFICIAL:&lt;sanityId&gt;". 빈 가용시간은 null. */
    private String venueRefId;

    /** "1부"/"2부"/"오후" 같은 세션 라벨(선택). */
    private String sessionLabel;

    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @Builder.Default
    private List<AvailabilityHold> holds = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void addHold(AvailabilityHold h) {
        h.setWindow(this);
        this.holds.add(h);
    }

    public void clearHolds() {
        this.holds.clear();
    }

    /** 외부/수동 점유 합계 — 모든 hold 의 인원 합(±빠른조정 + 외부예약). 정원 dot 의 '점유' 몫. */
    public int heldCount() {
        return holds.stream().mapToInt(AvailabilityHold::getCount).sum();
    }
}
