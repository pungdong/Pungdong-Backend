package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 이용 옵션의 평일/주말 하루 파트. 3축 중 ①가격(fee) ②시간({@link TimeMode}) ③주말(kind=WEEKEND 의
 * mode) 이 여기 모인다.
 *
 * <ul>
 *   <li>WEEKDAY: 항상 {@code sold=true}, mode ∈ {FIXED, OPEN}.</li>
 *   <li>WEEKEND: {@code sold=false}(주말 판매 안 함) 가능, mode ∈ {SAME(평일과 동일), FIXED, OPEN}.</li>
 * </ul>
 *
 * <p>{@code FIXED} → {@code timeBlocks}(부 리스트) 사용. {@code OPEN} → {@code openStart}~{@code openEnd}
 * + {@code holdHours}(키반납 N시간) 사용. {@code SAME} → 평일 구성을 따르고 {@code fee} 만 다를 수 있음.
 */
@Entity
@Table(name = "venue_daypart")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueDaypart {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private VenueTicket ticket;

    @Enumerated(EnumType.STRING)
    private DaypartKind kind;

    /** 판매 여부. WEEKDAY 는 항상 true, WEEKEND 만 false(주말·공휴일 불가) 가능. */
    private boolean sold;

    /** 입장료(원). 평일/주말 독립. */
    private Integer fee;

    @Enumerated(EnumType.STRING)
    private TimeMode timeMode;

    /** OPEN 모드: 오픈 시각. */
    private LocalTime openStart;

    /** OPEN 모드: 클로즈 시각. */
    private LocalTime openEnd;

    /** OPEN 모드: 입장 후 이용 가능 시간(키반납까지, 시간). */
    private Integer holdHours;

    @OneToMany(mappedBy = "daypart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<VenueTimeBlock> timeBlocks = new ArrayList<>();

    public void addTimeBlock(VenueTimeBlock block) {
        block.setDaypart(this);
        this.timeBlocks.add(block);
    }
}
