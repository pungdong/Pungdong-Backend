package com.diving.pungdong.venue.equipment;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사 × 위치 단위의 대여 장비 가격표 — "venue extension". 한 위치(official 이든 custom 이든)에서
 * 그 강사가 빌려주는 장비 목록 + 1인당 대여료를 담는다. <b>강사 전역</b>이라 그 강사의 모든 코스가
 * 공유한다(코스마다 복제하지 않음) — "어디서 바꿔도 신규 접수부터 적용".
 *
 * <p>위치 가격이 위치별로 다른 현실(딥스테이션=입장료 포함 무료 ↔ 5m풀=유료)을 코스가 아니라 여기서
 * 흡수한다. 위치는 {@code venueRefId}({@link com.diving.pungdong.venue.VenueScope} 토큰
 * {@code "CUSTOM:<pk>"}/{@code "OFFICIAL:<sanityId>"})로 가리킨다 — 코스 빌더 목록이 주는 그 값.
 *
 * <p>{@code (owner, venueRefId)} 유니크 = 강사·위치당 가격표 1장.
 */
@Entity
@Table(name = "venue_equipment_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_owner_venue_ref",
                columnNames = {"owner_id", "venue_ref_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueEquipmentProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Account owner;

    /** 위치 참조 토큰 — {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"}. */
    @Column(name = "venue_ref_id")
    private String venueRefId;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc, id asc")
    @Builder.Default
    private List<VenueEquipmentItem> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void addItem(VenueEquipmentItem item) {
        item.setProfile(this);
        this.items.add(item);
    }

    /** 수정(전량 교체 스냅샷) 전 자식 비우기 — orphanRemoval 로 DB 에서도 제거. */
    public void clearItems() {
        this.items.clear();
    }
}
