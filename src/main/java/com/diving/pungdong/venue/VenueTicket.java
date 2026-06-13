package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 이용 옵션 1종 = 한 카드 (예: 일반권 / 하프권 / 종일권). 권종은 새 축이 아니라 카드를 추가하는 것 —
 * 이용시간(3/5/9h)은 시간블록에서 파생된다(저장 안 함).
 *
 * <p>{@code disciplineCodes} = 이 이용 옵션이 적용되는 종목들. OFFICIAL 은 멀티 태그 가능
 * (같은 가격이면 한 카드에 여러 종목; 종목별 가격이 다르면 카드를 나눠 등록). CUSTOM 은 위치의
 * {@code lockedDisciplineCode} 1개로 강제된다.
 *
 * <p>가격·시간은 평일/주말 {@link VenueDaypart} 로 나뉜다 (WEEKDAY 1개 + 선택적 WEEKEND 1개).
 */
@Entity
@Table(name = "venue_ticket")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueTicket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    /** 이용 옵션 이름 (자유 텍스트, 예 "일반권"). 비워둘 수 있음(커스텀 단일 이용 옵션). */
    private String name;

    private int sortOrder;

    /** 적용 종목 코드 집합 ({@code discipline.code}). CUSTOM 은 1개로 고정. */
    @ElementCollection
    @CollectionTable(name = "venue_ticket_discipline", joinColumns = @JoinColumn(name = "ticket_id"))
    @Column(name = "discipline_code")
    @Builder.Default
    private Set<String> disciplineCodes = new LinkedHashSet<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VenueDaypart> dayparts = new ArrayList<>();

    public void addDaypart(VenueDaypart daypart) {
        daypart.setTicket(this);
        this.dayparts.add(daypart);
    }
}
