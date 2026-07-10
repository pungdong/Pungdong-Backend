package com.diving.pungdong.venue.equipment;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>위치에 강사가 덧붙이는 "확장(extension)" 레이어</b> — 한 위치(official/custom 무관) 위에 그 강사가
 * 정하는 추가 정보를 담는 그릇이고, <b>현재 그 내용물은 대여 장비뿐</b>이라 곧 "equipment extension"이다.
 * (위치에 매이는 그릇이라 venue 패키지 하위. 장차 위치별 강사 전용 다른 정보가 생기면 같은 그릇에 확장.)
 *
 * <p>장비 = 그 강사가 그 위치에서 빌려주는 항목 목록 + 1인당 대여료. <b>강사 전역(owner 종속)</b>이라
 * 그 강사의 모든 코스가 공유한다(코스마다 복제하지 않음) — "어디서 바꿔도 신규 접수부터 적용". 같은
 * 위치라도 강사마다 다른 가격을 가질 수 있다({@code (owner, venueRefId)} 유니크 = 강사·위치당 1장).
 *
 * <p>위치 가격이 위치별로 다른 현실(딥스테이션=입장료 포함 무료 ↔ 5m풀=유료)을 코스가 아니라 여기서
 * 흡수한다. 위치는 {@code venueRefId}({@link com.diving.pungdong.venue.VenueScope} 토큰
 * {@code "CUSTOM:<pk>"}/{@code "OFFICIAL:<sanityId>"})로 가리킨다 — 코스 빌더 목록이 주는 그 값.
 */
@Entity
@Table(name = "venue_equipment_extension",
        uniqueConstraints = @UniqueConstraint(name = "uk_owner_venue_ref",
                columnNames = {"owner_id", "venue_ref_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueEquipmentExtension {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Account owner;

    /** 위치 참조 토큰 — {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"}. */
    @Column(name = "venue_ref_id")
    private String venueRefId;

    @OneToMany(mappedBy = "extension", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<VenueEquipmentItem> items = new ArrayList<>();

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public void addItem(VenueEquipmentItem item) {
        item.setExtension(this);
        this.items.add(item);
    }

    /** 수정(전량 교체 스냅샷) 전 자식 비우기 — orphanRemoval 로 DB 에서도 제거. */
    public void clearItems() {
        this.items.clear();
    }
}
