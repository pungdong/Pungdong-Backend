package com.diving.pungdong.venue.favorite;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * <b>강사가 "자주 쓰는 위치"라고 직접 선언한 표식</b> — 코스빌더 위치 picker 의 "내 위치" 묶음을 채운다.
 * 위치는 {@code venueRefId}({@link com.diving.pungdong.venue.VenueScope} 토큰 {@code "CUSTOM:<pk>"} /
 * {@code "OFFICIAL:<sanityId>"})로 가리키므로 공식·커스텀을 한 테이블이 함께 담는다.
 *
 * <p><b>왜 {@code venue_equipment_extension}(같은 {@code (owner, venueRefId)} 유니크 조인)에 컬럼으로
 * 안 붙였나</b>: 장비 가격표는 코스 읽기에서 입장료·대여료를 합성하는 <i>사업 데이터</i>이고 즐겨찾기는
 * <i>UI 선호</i>다. 한 행에 섞으면 (1) {@code GET /venue-equipment} 가 items 0개짜리 "즐겨찾기용 껍데기"
 * 행을 뱉기 시작하고 (2) 즐겨찾기 해제가 장비 행을 남기고 장비 저장이 선호 행을 만들어 두 기능의
 * 수명주기가 엉킨다. 분리하면 즐겨찾기 조회가 문자열 집합 한 방이라 빌더 마킹도 단순하다.
 *
 * <p>표식이라 내용물이 없다 — {@code (owner, venueRefId)} 유니크가 곧 멱등성이다.
 */
@Entity
@Table(name = "venue_favorite",
        uniqueConstraints = @UniqueConstraint(name = "uk_favorite_owner_venue_ref",
                columnNames = {"owner_id", "venue_ref_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class VenueFavorite {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Account owner;

    /** 위치 참조 토큰 — {@code "CUSTOM:<pk>"} | {@code "OFFICIAL:<sanityId>"}. */
    @Column(name = "venue_ref_id")
    private String venueRefId;

    private OffsetDateTime createdAt;
}
