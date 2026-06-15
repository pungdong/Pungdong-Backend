package com.diving.pungdong.venue;

import lombok.*;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * 이용권 공개 안정 식별자 — 코스({@code RoundVenueTicket.ticketRef})·수강신청({@code Enrollment.ticketRef})이
     * 이 값으로 이용권을 참조한다. <b>PK 가 아니라 별도 UUID</b>인 이유: 위치 수정은 자식 전량 교체(삭제·재삽입)라
     * PK 는 매번 바뀌지만, {@code ref} 는 수정 요청이 들고 온 기존 값을 {@link VenueService} 가 보존하므로
     * 재생성에도 살아남는다 → 그 이용권을 가리키던 코스/신청 참조가 안 깨진다. (OFFICIAL/Sanity 의 {@code _key}
     * 와 같은 "안정 문자열키" 역할.) 어떤 저장 경로(서비스/직접 repo save)든 비어 있으면 {@code @PrePersist}
     * 가 새로 발급한다. UUID 라 unique 제약 없이도 충돌 사실상 불가 — 전량교체의 delete+insert 플러시 순서
     * 문제도 피한다.
     */
    @Column(updatable = false)
    private String ref;

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

    /** 저장 직전 안정 ref 보장 — 서비스가 보존값을 채웠으면 그대로, 아니면(신규/직접 save) 새 UUID. */
    @PrePersist
    void assignRefIfAbsent() {
        if (ref == null || ref.isBlank()) {
            ref = UUID.randomUUID().toString();
        }
    }
}
