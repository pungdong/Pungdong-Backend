package com.diving.pungdong.venue;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사 커스텀(CUSTOM) 위치 1건 — 해양 세션·다이빙 포인트처럼 강사가 직접 추가하는 비공개 위치.
 * 만든 강사({@code owner})에게만 종속되고, 한 종목({@code lockedDisciplineCode})으로 잠긴다.
 *
 * <p><b>공식(OFFICIAL) 수영장은 여기 없다</b> — Sanity 가 authoring 한다(`sanity/schemas/venue.ts`).
 * BE DB 는 강사 커스텀 위치만 보유. 코스 빌더는 Sanity OFFICIAL + 강사 본인 CUSTOM 을 합쳐 본다.
 * 정책/분담은 docs/features/venue.md, 동기화 설계는 그 문서의 "캐싱·동기화·모니터링 설계".
 *
 * <p>{@link com.diving.pungdong.instructorapplication.InstructorApplication} 와 동일 패턴 —
 * Account 단방향 참조, Discipline 코드 참조, 자식 cascade.
 */
@Entity
@Table(name = "venue")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Venue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 장소 이름 (예: "울릉도 죽도 포인트"). */
    private String name;

    @Enumerated(EnumType.STRING)
    private VenueType type;

    private String address;
    private Double latitude;
    private Double longitude;

    /** 소유 강사 (커스텀 위치는 항상 owner 가 있다). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Account owner;

    /** 잠긴 종목 코드 ({@code discipline.code}) — 이 위치의 모든 이용 옵션이 이 종목으로 고정. */
    private String lockedDisciplineCode;

    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<VenueTicket> tickets = new ArrayList<>();

    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VenueClosure> closures = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void addTicket(VenueTicket ticket) {
        ticket.setVenue(this);
        this.tickets.add(ticket);
    }

    public void addClosure(VenueClosure closure) {
        closure.setVenue(this);
        this.closures.add(closure);
    }

    /** 수정(전량 교체 스냅샷) 전 자식 비우기 — orphanRemoval 로 DB 에서도 제거된다. */
    public void clearChildren() {
        this.tickets.clear();
        this.closures.clear();
    }
}
