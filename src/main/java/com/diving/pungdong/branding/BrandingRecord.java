package com.diving.pungdong.branding;

import lombok.*;

import javax.persistence.*;

/**
 * 공식 기록 1건 — "🥇 CWT -75m" 같은 chip 한 개. 강사 전용이 아니라 <b>일반 유저도</b> 쓴다(D2 —
 * 아마추어 대회 기록 어필).
 *
 * <p><b>{@code value} 가 문자열인 이유</b>: 종목마다 단위가 다르다 — 깊이({@code "-75m"}, 음수),
 * 거리({@code "180m"}), 시간({@code "6:24"}). 숫자로 정규화하면 표시가 깨지고, BE 가 포맷을 재구현하면
 * FE 와 어긋난다. 그래서 저장·표시 모두 원문 그대로 간다.
 */
@Entity
@Table(name = "branding_record")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class BrandingRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branding_id", nullable = false)
    private AccountBranding branding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Medal medal;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, length = 16)
    private RecordEventCode eventCode;

    /**
     * 기록값 원문 — 단위가 종목마다 달라 문자열로 둔다(위 클래스 주석).
     *
     * <p>컬럼명이 {@code record_value} 인 이유: {@code value} 는 H2(테스트 DB)의 <b>예약어</b>라
     * 그대로 쓰면 스키마 생성이 깨진다. API 필드명은 계약대로 {@code value} 를 유지한다.
     */
    @Column(name = "record_value", nullable = false, length = 16)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
