package com.diving.pungdong.availability;

import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 수동/외부 점유 1건 — "두 가지 점유 조정 방식"을 <b>단일 테이블</b>로 흡수(브리프). 일정({@link
 * AvailabilitySession})에 붙는다.
 *
 * <ul>
 *   <li>{@code memo == null} — ± 빠른조정. 메모 없이 1칸 점유({@code count=1}).</li>
 *   <li>{@code memo != null} — 외부예약. 메모 + 인원 1~N. 유효정원 초과해도 기록(자동확장 없음).</li>
 * </ul>
 *
 * <p>풍덩 수강생 점유(enrollment)와는 별개 — 이건 "풍덩 밖 점유"를 강사가 직접 기입(외부=차콜로 분리 표시).
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
    @JoinColumn(name = "session_id")
    private AvailabilitySession session;

    /** 점유 인원 — ± 빠른조정 = 1, 외부예약 = 1~N. */
    private int count;

    /** 외부예약 메모(예: "네이버 L1 홍길동"). null 이면 ± 빠른조정. */
    private String memo;

    private LocalDateTime createdAt;

    /** 외부 출처 점유인가 — 메모 동반 외부예약(±빠른조정과 구분). 표시/집계용. */
    public boolean isExternal() {
        return memo != null && !memo.isBlank();
    }
}
