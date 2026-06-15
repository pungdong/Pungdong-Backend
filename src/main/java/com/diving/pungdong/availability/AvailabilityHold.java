package com.diving.pungdong.availability;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 수동/외부 점유 1건 — V2 디자인 "두 가지 정원 조정 방식"을 <b>단일 테이블</b>로 흡수한다(브리프).
 *
 * <ul>
 *   <li>{@code memo == null} — ± 빠른조정. 메모 없이 정원 1칸 점유(일상 케이스, {@code count=1}).</li>
 *   <li>{@code memo != null} — 외부예약. 메모 + 인원 1~N(단체 커버). 정원 초과 시 window 정원 자동 확장.</li>
 * </ul>
 *
 * <p>풍덩 수강생 점유(enrollment)와는 별개 — 그건 미래 enrollment 도메인이 소유한다. 이 hold 는
 * "풍덩 밖에서 일어난 점유"를 강사가 직접 기입하는 것이라 캘린더 정원 dot 에서 <b>외부(차콜)</b>로 분리된다.
 */
@Entity
@Table(name = "availability_hold")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AvailabilityHold {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "window_id")
    private AvailabilityWindow window;

    /** 점유 인원 — ± 빠른조정 = 1, 외부예약 = 1~N. */
    private int count;

    /** 외부예약 메모(예: "네이버 L1 홍길동"). null 이면 ± 빠른조정(메모 없는 단순 점유). */
    private String memo;

    private LocalDateTime createdAt;

    /** 외부 출처 점유인가 — 메모를 동반한 외부예약(±빠른조정과 구분). 표시/집계용. */
    public boolean isExternal() {
        return memo != null && !memo.isBlank();
    }
}
